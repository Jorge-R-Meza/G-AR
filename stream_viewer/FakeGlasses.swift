//
//  FakeGlasses.swift
//
//  Stands in for the M6 glasses' camera stream so the viewer can be tested without them.
//  Encodes a test pattern as H.264 and sends it exactly the way the glasses do:
//  one UDP datagram per encoder buffer, a 0x00 type byte in front, SPS/PPS in their
//  own datagram before every keyframe.
//
//  Build:  swiftc -O FakeGlasses.swift -o fake-glasses
//  Run:    ./fake-glasses [--host 127.0.0.1] [--port 32888] [--size 1280x720] [--seconds 10]
//                         [--qr "text"]     puts a QR code holding that text in the picture
//                         [--ean13 DIGITS]  puts a retail barcode in the picture: 13 digits,
//                                           or the 12 of a UPC-A, as on a medicine package
//

import AppKit
import CoreImage
import CoreMedia
import VideoToolbox

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data((message + "\n").utf8))
    exit(2)
}

var host = "127.0.0.1"
var port: UInt16 = 32888
var width = 1280, height = 720
var seconds = 10.0
var qrText: String?
var eanDigits: String?
let framesPerSecond = 24       // same as the glasses
let bitRate = 4_000_000        // same as the glasses

var args = Array(CommandLine.arguments.dropFirst())
while !args.isEmpty {
    let flag = args.removeFirst()
    guard !args.isEmpty else { fail("\(flag) needs a value") }
    let value = args.removeFirst()
    switch flag {
    case "--host": host = value
    case "--port": port = UInt16(value) ?? port
    case "--size":
        let parts = value.split(separator: "x").compactMap { Int($0) }
        guard parts.count == 2 else { fail("--size looks like 1280x720") }
        (width, height) = (parts[0], parts[1])
    case "--seconds": seconds = Double(value) ?? seconds
    case "--qr": qrText = value
    case "--ean13": eanDigits = value.count == 12 ? "0" + value : value
    default: fail("Unknown option \(flag)")
    }
}

// MARK: - UDP

let descriptor = socket(AF_INET, SOCK_DGRAM, 0)
var sendBuffer: Int32 = 1 << 20   // macOS caps UDP datagrams at 9 KB unless the send buffer is raised
setsockopt(descriptor, SOL_SOCKET, SO_SNDBUF, &sendBuffer, socklen_t(MemoryLayout<Int32>.size))
var destination = sockaddr_in()
destination.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
destination.sin_family = sa_family_t(AF_INET)
destination.sin_port = port.bigEndian
guard inet_pton(AF_INET, host, &destination.sin_addr) == 1 else { fail("--host must be an IPv4 address") }

var sentPackets = 0, failedPackets = 0, largestPacket = 0
func sendVideo(_ payload: [UInt8]) {
    let packet = [UInt8(0)] + payload
    let sent = withUnsafePointer(to: &destination) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
            sendto(descriptor, packet, packet.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
        }
    }
    if sent < 0 { failedPackets += 1 } else { sentPackets += 1 }
    largestPacket = max(largestPacket, packet.count)
}

// MARK: - Encoder

let startCode: [UInt8] = [0, 0, 0, 1]

func parameterSets(of sample: CMSampleBuffer) -> [UInt8]? {
    guard let format = CMSampleBufferGetFormatDescription(sample) else { return nil }
    var result: [UInt8] = []
    for index in 0..<2 {
        var pointer: UnsafePointer<UInt8>?
        var size = 0
        guard CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, parameterSetIndex: index, parameterSetPointerOut: &pointer,
                                                                 parameterSetSizeOut: &size, parameterSetCountOut: nil,
                                                                 nalUnitHeaderLengthOut: nil) == noErr, let pointer else { return nil }
        result += startCode + Array(UnsafeBufferPointer(start: pointer, count: size))
    }
    return result
}

/// Length-prefixed NAL units (what VideoToolbox produces) to Annex B (what Android's MediaCodec produces).
func annexB(of sample: CMSampleBuffer) -> [UInt8]? {
    guard let block = CMSampleBufferGetDataBuffer(sample) else { return nil }
    let length = CMBlockBufferGetDataLength(block)
    var bytes = [UInt8](repeating: 0, count: length)
    guard CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: length, destination: &bytes) == noErr else { return nil }
    var result: [UInt8] = []
    result.reserveCapacity(length + 16)
    var offset = 0
    while offset + 4 <= length {
        let size = Int(bytes[offset]) << 24 | Int(bytes[offset + 1]) << 16 | Int(bytes[offset + 2]) << 8 | Int(bytes[offset + 3])
        offset += 4
        guard offset + size <= length else { break }
        result += startCode + bytes[offset..<offset + size]
        offset += size
    }
    return result
}

var session: VTCompressionSession?
guard VTCompressionSessionCreate(allocator: nil, width: Int32(width), height: Int32(height), codecType: kCMVideoCodecType_H264,
                                 encoderSpecification: nil, imageBufferAttributes: nil, compressedDataAllocator: nil,
                                 outputCallback: nil, refcon: nil, compressionSessionOut: &session) == noErr,
      let session else { fail("Couldn't create an H.264 encoder") }
VTSessionSetProperty(session, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
VTSessionSetProperty(session, key: kVTCompressionPropertyKey_ProfileLevel, value: kVTProfileLevel_H264_Baseline_AutoLevel)
VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AverageBitRate, value: bitRate as CFNumber)
VTSessionSetProperty(session, key: kVTCompressionPropertyKey_ExpectedFrameRate, value: framesPerSecond as CFNumber)
VTSessionSetProperty(session, key: kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, value: 2 as CFNumber)

// MARK: - Test pattern

var pixelBuffer: CVPixelBuffer?
let attributes: [CFString: Any] = [kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
                                   kCVPixelBufferCGBitmapContextCompatibilityKey: true]
guard CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_32BGRA, attributes as CFDictionary,
                          &pixelBuffer) == kCVReturnSuccess else { fail("Couldn't create a frame buffer") }

/// The QR code for --qr, one pixel per module.
let qrImage: CGImage? = qrText.flatMap { text in
    guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
    filter.setValue(Data(text.utf8), forKey: "inputMessage")
    filter.setValue("M", forKey: "inputCorrectionLevel")
    guard let output = filter.outputImage else { return nil }
    return CIContext().createCGImage(output, from: output.extent)
}
if qrText != nil && qrImage == nil { fail("Couldn't make a QR code for --qr") }

/// The bars of the barcode for --ean13: 95 modules, true where a module is dark.
let eanModules: [Bool]? = eanDigits.map { text in
    let digits = text.compactMap(\.wholeNumberValue)
    guard digits.count == 13, text.count == 13 else { fail("--ean13 takes 13 digits, or 12 for a UPC-A") }
    // How each digit is drawn in the left half; the right half is the same with dark and light swapped.
    let odd = ["0001101", "0011001", "0010011", "0111101", "0100011", "0110001", "0101111", "0111011", "0110111", "0001011"]
    // The first digit isn't drawn: it chooses which left-half digits are drawn the other way (G).
    let parity = ["LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG", "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL"]
    func right(_ digit: Int) -> String { String(odd[digit].map { $0 == "0" ? "1" : "0" }) }
    var bits = "101"
    for (index, set) in parity[digits[0]].enumerated() {
        let digit = digits[index + 1]
        bits += set == "L" ? odd[digit] : String(right(digit).reversed())
    }
    bits += "01010"
    for digit in digits[7...] { bits += right(digit) }
    bits += "101"
    return bits.map { $0 == "1" }
}

func draw(frame: Int, into buffer: CVPixelBuffer) {
    CVPixelBufferLockBaseAddress(buffer, [])
    defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
    guard let context = CGContext(data: CVPixelBufferGetBaseAddress(buffer), width: width, height: height, bitsPerComponent: 8,
                                  bytesPerRow: CVPixelBufferGetBytesPerRow(buffer), space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
    else { return }
    let colors: [CGColor] = [.init(red: 0.75, green: 0.75, blue: 0.75, alpha: 1), .init(red: 0.75, green: 0.75, blue: 0, alpha: 1),
                             .init(red: 0, green: 0.75, blue: 0.75, alpha: 1), .init(red: 0, green: 0.75, blue: 0, alpha: 1),
                             .init(red: 0.75, green: 0, blue: 0.75, alpha: 1), .init(red: 0.75, green: 0, blue: 0, alpha: 1),
                             .init(red: 0, green: 0, blue: 0.75, alpha: 1)]
    let barWidth = CGFloat(width) / CGFloat(colors.count)
    for (index, color) in colors.enumerated() {
        context.setFillColor(color)
        context.fill(CGRect(x: CGFloat(index) * barWidth, y: 0, width: barWidth + 1, height: CGFloat(height)))
    }
    // A square that crosses the picture every two seconds, so motion (and dropped frames) are visible.
    let side = CGFloat(height) / 6
    let x = CGFloat(frame % (framesPerSecond * 2)) / CGFloat(framesPerSecond * 2) * (CGFloat(width) - side)
    context.setFillColor(.white)
    context.fill(CGRect(x: x, y: CGFloat(height) / 2 - side / 2, width: side, height: side))

    // The QR code sits on a white card in the top-right corner, clear of the moving square.
    if let qrImage {
        let card = CGFloat(height) / 3
        let cardRect = CGRect(x: CGFloat(width) - card * 1.2, y: CGFloat(height) - card * 1.15, width: card, height: card)
        context.setFillColor(.white)
        context.fill(cardRect)
        context.interpolationQuality = .none   // keep the modules' edges sharp
        context.draw(qrImage, in: cardRect.insetBy(dx: card / 10, dy: card / 10))
    }

    // The barcode sits on a white card in the top-left corner, with the clear margin a reader needs.
    if let eanModules {
        let module = max(2, CGFloat(Int(CGFloat(width) / 4 / CGFloat(eanModules.count))))   // whole pixels, so bars stay sharp
        let margin = module * 11
        let cardRect = CGRect(x: CGFloat(height) / 12, y: CGFloat(height) * 0.62,
                              width: module * CGFloat(eanModules.count) + margin * 2, height: CGFloat(height) / 4)
        context.setFillColor(.white)
        context.fill(cardRect)
        context.setFillColor(.black)
        for (index, dark) in eanModules.enumerated() where dark {
            context.fill(CGRect(x: cardRect.minX + margin + CGFloat(index) * module, y: cardRect.minY + module * 4,
                                width: module, height: cardRect.height - module * 8))
        }
    }

    let graphics = NSGraphicsContext(cgContext: context, flipped: false)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = graphics
    let text = "FAKE GLASSES  \(width)x\(height)  frame \(frame)" as NSString
    let font = NSFont.monospacedDigitSystemFont(ofSize: CGFloat(height) / 14, weight: .bold)
    context.setFillColor(.black)
    context.fill(CGRect(x: 0, y: 0, width: CGFloat(width), height: font.pointSize * 1.8))
    text.draw(at: NSPoint(x: font.pointSize * 0.6, y: font.pointSize * 0.3),
              withAttributes: [.font: font, .foregroundColor: NSColor.white])
    NSGraphicsContext.restoreGraphicsState()
}

// MARK: - Run

let total = Int(seconds * Double(framesPerSecond))
print("Sending \(width)x\(height) H.264 at \(framesPerSecond) fps to \(host):\(port) for \(Int(seconds)) s.")
let begin = Date()
for frame in 0..<total {
    draw(frame: frame, into: pixelBuffer!)
    let time = CMTime(value: CMTimeValue(frame), timescale: CMTimeScale(framesPerSecond))
    VTCompressionSessionEncodeFrame(session, imageBuffer: pixelBuffer!, presentationTimeStamp: time, duration: .invalid,
                                    frameProperties: nil, infoFlagsOut: nil) { status, _, sample in
        guard status == noErr, let sample, let data = annexB(of: sample) else { return }
        let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) as? [[CFString: Any]]
        let isKeyframe = !(attachments?.first?[kCMSampleAttachmentKey_NotSync] as? Bool ?? false)
        if isKeyframe, let config = parameterSets(of: sample) {
            sendVideo(config)
        }
        sendVideo(data)
    }
    // One frame in flight at a time, like the glasses' encoder callback.
    VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: time)
    let due = begin.addingTimeInterval(Double(frame + 1) / Double(framesPerSecond))
    Thread.sleep(until: due)
}
VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid)
print("Sent \(sentPackets) packets (largest \(largestPacket) bytes), \(failedPackets) failed to send.")
