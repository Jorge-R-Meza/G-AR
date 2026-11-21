//
//  StreamViewer.swift
//
//  Shows the M6 glasses' live camera stream on this Mac and reads the QR codes in it.
//  Each code is outlined in the window and printed once when it comes into view.
//  When a code holds a web link, the link is fetched and the response is saved in the
//  reports folder (next to this program unless --reports says otherwise). A report is
//  saved again only when it has changed.
//  A report that is JSON is also sent to the app on the glasses, which shows the patient on
//  its cards: over TCP to port 32889 at the address the stream comes from (or --glasses).
//
//  It also reads the barcodes on medicine packages. Each one is looked up in the FDA's drug
//  directory (openFDA); what the FDA lists is saved in the reports folder as
//  medicine-<barcode>.json and sent to the glasses app the same way, which shows the medicine
//  on its cards in place of the patient.
//
//  The glasses send one UDP datagram per encoded frame to <target_host>:32888:
//      byte 0     packet type: 0 = video, 1 = audio, 3 = screenshot fragment
//      byte 1...  for video, one H.264 buffer in Annex B form (start codes).
//                 SPS/PPS arrive in their own datagram before every keyframe.
//  (Read from the kit's MLScreenCapture APK: ProtocolManager / CameraPreviewStream.)
//
//  Build:  swiftc -O StreamViewer.swift -o stream-viewer
//  Run:    ./stream-viewer [--port 32888] [--barcodes] [--dump out.h264] [--save-frame latest.jpg]
//

import AppKit
import CoreMedia
import ImageIO
import Network
import VideoToolbox
import Vision

// MARK: - Options

struct Options {
    var port: UInt16 = 32888
    var dumpPath: String?
    var framePath: String?
    var saveInterval: Double = 0.5
    var window = true
    var quitAfter: Int?
    var detect = true
    var barcodes = false
    var fetch = true
    var reportsPath: String?
    var onlyHost: String?
    var send = true
    var glassesHost: String?
    var medicines = true
}

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data((message + "\n").utf8))
    exit(2)
}

let usage = """
Usage: stream-viewer [options]
  --port N              UDP port to listen on (default 32888, the glasses' fixed port)
  --barcodes            read every kind of barcode, not only QR codes and medicine barcodes
  --no-medicines        don't read medicine barcodes or look them up at the FDA
  --no-detect           only show the video
  --no-fetch            read codes but don't fetch the links in them
  --only-host HOST      only fetch links to this host, such as reports.example.com
  --reports DIR         where fetched reports are saved (default: reports, next to this program)
  --glasses HOST        where the glasses app is, to send reports to (default: where the stream comes from)
  --no-send             don't send fetched reports to the glasses app
  --dump FILE           also write the raw H.264 stream to FILE (plays in VLC / ffplay)
  --save-frame FILE     keep FILE updated with the latest frame as a JPEG
  --save-interval SEC   how often to rewrite it (default 0.5)
  --no-window           don't open a window (use with --dump or --save-frame)
  --quit-after N        exit after N decoded frames
"""

func parseOptions() -> Options {
    var options = Options()
    var args = Array(CommandLine.arguments.dropFirst())
    func value(for flag: String) -> String {
        guard !args.isEmpty else { fail("\(flag) needs a value\n\n\(usage)") }
        return args.removeFirst()
    }
    while !args.isEmpty {
        let flag = args.removeFirst()
        switch flag {
        case "--port":
            guard let port = UInt16(value(for: flag)) else { fail("--port must be 1-65535") }
            options.port = port
        case "--dump": options.dumpPath = value(for: flag)
        case "--save-frame": options.framePath = value(for: flag)
        case "--save-interval":
            guard let seconds = Double(value(for: flag)), seconds > 0 else { fail("--save-interval must be a positive number") }
            options.saveInterval = seconds
        case "--no-window": options.window = false
        case "--barcodes": options.barcodes = true
        case "--no-medicines": options.medicines = false
        case "--no-detect": options.detect = false
        case "--no-fetch": options.fetch = false
        case "--only-host": options.onlyHost = value(for: flag).lowercased()
        case "--reports": options.reportsPath = value(for: flag)
        case "--glasses": options.glassesHost = value(for: flag)
        case "--no-send": options.send = false
        case "--quit-after":
            guard let count = Int(value(for: flag)), count > 0 else { fail("--quit-after must be a positive number") }
            options.quitAfter = count
        case "-h", "--help":
            print(usage)
            exit(0)
        default:
            fail("Unknown option \(flag)\n\n\(usage)")
        }
    }
    return options
}

// MARK: - Counters shared between the network thread and the main thread

final class Stats {
    private let lock = NSLock()
    private var packets = 0, bytes = 0, frames = 0, totalFrames = 0, decodeErrors = 0, other = 0
    private var size = CGSize.zero
    private var sender = ""

    func packet(bytes count: Int, from address: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        packets += 1
        bytes += count
        let isNew = address != sender
        sender = address
        return isNew
    }
    /// The address the stream last came from, or "" before the first packet.
    var streamSource: String {
        lock.lock(); defer { lock.unlock() }
        return sender
    }
    func otherPacket() { lock.lock(); other += 1; lock.unlock() }
    func decodeError() { lock.lock(); decodeErrors += 1; lock.unlock() }
    /// Returns the number of frames decoded so far.
    func frame(_ size: CGSize) -> Int {
        lock.lock(); defer { lock.unlock() }
        frames += 1
        totalFrames += 1
        self.size = size
        return totalFrames
    }

    struct Snapshot { var packets, bytes, frames, totalFrames, decodeErrors, other: Int; var size: CGSize; var sender: String }
    /// Per-interval counters reset on every call; totals don't.
    func take() -> Snapshot {
        lock.lock(); defer { lock.unlock() }
        let snapshot = Snapshot(packets: packets, bytes: bytes, frames: frames, totalFrames: totalFrames,
                                decodeErrors: decodeErrors, other: other, size: size, sender: sender)
        packets = 0; bytes = 0; frames = 0; decodeErrors = 0; other = 0
        return snapshot
    }
}

// MARK: - H.264 (Annex B in, pixel buffers out)

/// Splits an Annex B buffer into NAL units (without their start codes).
func splitAnnexB(_ data: [UInt8]) -> [ArraySlice<UInt8>] {
    var nals: [ArraySlice<UInt8>] = []
    let count = data.count
    var start = -1
    var i = 0
    func close(at end: Int) {
        guard start >= 0 else { return }
        var last = end
        while last > start && data[last - 1] == 0 { last -= 1 }   // a NAL never ends in 0x00
        if last > start { nals.append(data[start..<last]) }
    }
    while i + 2 < count {
        if data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1 {
            close(at: i)
            start = i + 3
            i += 3
        } else {
            i += 1
        }
    }
    close(at: count)
    return nals
}

final class H264Decoder {
    var onFrame: ((CVPixelBuffer) -> Void)?
    var onError: (() -> Void)?

    private var sps: [UInt8]?
    private var pps: [UInt8]?
    private var format: CMVideoFormatDescription?
    private var session: VTDecompressionSession?
    private var needKeyframe = true
    private var warnedNoStartCode = false

    func feed(_ payload: [UInt8]) {
        let nals = splitAnnexB(payload)
        if nals.isEmpty {
            if !warnedNoStartCode {
                warnedNoStartCode = true
                print("Video packet without H.264 start codes (\(payload.count) bytes); ignoring such packets.")
            }
            return
        }

        var slices: [ArraySlice<UInt8>] = []
        var hasKeyframe = false
        var parametersChanged = false
        for nal in nals {
            guard let header = nal.first else { continue }
            switch header & 0x1F {
            case 7:
                let bytes = Array(nal)
                if sps != bytes { sps = bytes; parametersChanged = true }
            case 8:
                let bytes = Array(nal)
                if pps != bytes { pps = bytes; parametersChanged = true }
            case 5:
                hasKeyframe = true
                slices.append(nal)
            case 1:
                slices.append(nal)
            default:
                break   // SEI, access unit delimiters, ...
            }
        }

        if parametersChanged || session == nil, let sps, let pps {
            makeSession(sps: sps, pps: pps)
        }
        guard !slices.isEmpty, let session, let format else { return }
        if needKeyframe {
            guard hasKeyframe else { return }   // can't start mid-stream
            needKeyframe = false
        }

        // VideoToolbox wants each NAL prefixed with its 4-byte length instead of a start code.
        var sampleBytes: [UInt8] = []
        sampleBytes.reserveCapacity(payload.count + 4 * slices.count)
        for nal in slices {
            let length = UInt32(nal.count)
            sampleBytes.append(UInt8(truncatingIfNeeded: length >> 24))
            sampleBytes.append(UInt8(truncatingIfNeeded: length >> 16))
            sampleBytes.append(UInt8(truncatingIfNeeded: length >> 8))
            sampleBytes.append(UInt8(truncatingIfNeeded: length))
            sampleBytes.append(contentsOf: nal)
        }

        var block: CMBlockBuffer?
        guard CMBlockBufferCreateWithMemoryBlock(allocator: kCFAllocatorDefault, memoryBlock: nil,
                                                 blockLength: sampleBytes.count, blockAllocator: kCFAllocatorDefault,
                                                 customBlockSource: nil, offsetToData: 0, dataLength: sampleBytes.count,
                                                 flags: kCMBlockBufferAssureMemoryNowFlag, blockBufferOut: &block) == noErr,
              let block,
              CMBlockBufferReplaceDataBytes(with: sampleBytes, blockBuffer: block, offsetIntoDestination: 0,
                                            dataLength: sampleBytes.count) == noErr
        else { return }

        var sample: CMSampleBuffer?
        var sampleSize = sampleBytes.count
        guard CMSampleBufferCreateReady(allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format,
                                        sampleCount: 1, sampleTimingEntryCount: 0, sampleTimingArray: nil,
                                        sampleSizeEntryCount: 1, sampleSizeArray: &sampleSize,
                                        sampleBufferOut: &sample) == noErr,
              let sample
        else { return }

        var failed = false
        let status = VTDecompressionSessionDecodeFrame(session, sampleBuffer: sample, flags: [], infoFlagsOut: nil) {
            [weak self] status, _, image, _, _ in
            if status == noErr, let image {
                self?.onFrame?(image)
            } else {
                failed = true
            }
        }
        if status != noErr || failed {
            // Usually a lost frame: wait for the next keyframe instead of showing garbage.
            needKeyframe = true
            onError?()
        }
    }

    private func makeSession(sps: [UInt8], pps: [UInt8]) {
        if let session { VTDecompressionSessionInvalidate(session) }
        session = nil
        format = nil
        needKeyframe = true

        var newFormat: CMVideoFormatDescription?
        let status = sps.withUnsafeBufferPointer { spsPointer in
            pps.withUnsafeBufferPointer { ppsPointer in
                let pointers = [spsPointer.baseAddress!, ppsPointer.baseAddress!]
                let sizes = [spsPointer.count, ppsPointer.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault, parameterSetCount: 2, parameterSetPointers: pointers,
                    parameterSetSizes: sizes, nalUnitHeaderLength: 4, formatDescriptionOut: &newFormat)
            }
        }
        guard status == noErr, let newFormat else {
            print("Couldn't read the stream's SPS/PPS (error \(status)).")
            return
        }

        let attributes: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
            kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
        ]
        var newSession: VTDecompressionSession?
        let created = VTDecompressionSessionCreate(allocator: kCFAllocatorDefault, formatDescription: newFormat,
                                                   decoderSpecification: nil, imageBufferAttributes: attributes as CFDictionary,
                                                   outputCallback: nil, decompressionSessionOut: &newSession)
        guard created == noErr, let newSession else {
            print("Couldn't create an H.264 decoder (error \(created)).")
            return
        }
        format = newFormat
        session = newSession
        let size = CMVideoFormatDescriptionGetDimensions(newFormat)
        print("Stream format: H.264 \(size.width)x\(size.height)")
    }
}

// MARK: - QR codes and barcodes

struct DetectedCode {
    /// "QR", "EAN13", "Code128", ...
    var kind: String
    var payload: String
    /// The code's four corners, in order around it, as fractions of the picture's width and
    /// height measured from the bottom-left corner (Vision's coordinates).
    var corners: [CGPoint]
}

final class CodeDetector {
    /// Called on the detector's own queue after every scan, with no codes if none were found.
    var onCodes: (([DetectedCode]) -> Void)?

    private let symbologies: [VNBarcodeSymbology]?
    private let queue = DispatchQueue(label: "code-detector")
    private let lock = NSLock()
    private var busy = false

    /// `nil` reads every kind of code Vision knows.
    init(symbologies: [VNBarcodeSymbology]?) {
        self.symbologies = symbologies
    }

    /// Scans the frame, unless the previous one is still being scanned: the video never waits.
    func offer(_ pixelBuffer: CVPixelBuffer) {
        lock.lock()
        let free = !busy
        if free { busy = true }
        lock.unlock()
        guard free else { return }
        queue.async { [self] in
            let codes = scan(pixelBuffer)
            lock.lock(); busy = false; lock.unlock()
            onCodes?(codes)
        }
    }

    private func scan(_ pixelBuffer: CVPixelBuffer) -> [DetectedCode] {
        let request = VNDetectBarcodesRequest()
        if let symbologies { request.symbologies = symbologies }
        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, options: [:])
        guard (try? handler.perform([request])) != nil else { return [] }
        return (request.results ?? []).compactMap { found in
            guard let payload = found.payloadStringValue else { return nil }
            let kind = found.symbology.rawValue.replacingOccurrences(of: "VNBarcodeSymbology", with: "")
            return DetectedCode(kind: kind, payload: payload,
                                corners: [found.topLeft, found.topRight, found.bottomRight, found.bottomLeft])
        }
    }
}

/// Prints a code when it comes into view, not on every frame it stays there.
final class CodeLog {
    private var lastSeen: [String: Date] = [:]

    /// Returns the codes that have just come into view.
    func note(_ codes: [DetectedCode]) -> [DetectedCode] {
        let now = Date()
        var arrived: [DetectedCode] = []
        for code in codes {
            let key = code.kind + "\n" + code.payload
            if now.timeIntervalSince(lastSeen[key] ?? .distantPast) > 2 {
                print("\(code.kind): \(code.payload)")
                arrived.append(code)
            }
            lastSeen[key] = now
        }
        return arrived
    }
}

// MARK: - Reports (what a scanned link returns)

/// Fetches the link in a scanned code and saves the response, but only when it has changed.
///
/// The reports folder holds one file per link, named after the link, with that link's
/// current response; and `latest.json`, a copy of the report scanned most recently. Both are
/// `.txt` instead when the response isn't JSON. A file is rewritten only when its contents
/// would change, so a file's modification time is when that report last changed.
///
/// When the server sends an ETag, it is sent back on the next check of that link, and a
/// server that answers "304 Not Modified" spares the download as well.
final class ReportFetcher {
    private static let sizeLimit = 5 << 20
    /// A code that stays near the camera drops in and out of view; don't check it each time.
    private static let recheckAfter: TimeInterval = 10

    private let folder: URL
    private let onlyHost: String?
    private let session: URLSession
    private let lock = NSLock()
    private var lastCheck: [String: Date] = [:]
    private var etags: [String: String] = [:]
    /// Called with each JSON report as it becomes the latest, whether or not it has changed.
    var onReport: ((Data) -> Void)?

    init(folder: URL, onlyHost: String?) {
        self.folder = folder
        self.onlyHost = onlyHost
        let configuration = URLSessionConfiguration.ephemeral   // no cookies kept
        // No cache either: the saved file is the cache, and a 304 must reach this code.
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 10
        configuration.timeoutIntervalForResource = 20
        session = URLSession(configuration: configuration)
    }

    func fetch(_ payload: String) {
        let text = payload.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: text), let scheme = url.scheme?.lowercased(), ["http", "https"].contains(scheme),
              let host = url.host?.lowercased() else { return }   // not a web link
        if let onlyHost, host != onlyHost {
            print("Not fetching \(host): --only-host allows \(onlyHost) only.")
            return
        }
        lock.lock()
        let recent = Date().timeIntervalSince(lastCheck[text] ?? .distantPast) < Self.recheckAfter
        if !recent { lastCheck[text] = Date() }
        let etag = etags[text]
        lock.unlock()
        guard !recent else { return }

        let name = fileName(for: url, host: host)
        var request = URLRequest(url: url)
        // Only ask "has it changed?" while the copy the ETag belongs to is still on disk.
        let saved = savedFile(named: name)
        if let etag, saved != nil {
            request.setValue(etag, forHTTPHeaderField: "If-None-Match")
        }
        print("Checking \(text)")
        session.dataTask(with: request) { [self] data, response, error in
            if let error {
                print("Couldn't fetch \(host): \(error.localizedDescription)")
                return
            }
            let http = response as? HTTPURLResponse
            let status = http?.statusCode ?? 0
            if status == 304, let saved, let contents = try? Data(contentsOf: saved) {
                print("Report unchanged, not downloaded: \(saved.path)")
                try? makeLatest(contents, type: saved.pathExtension)
                return
            }
            guard let data, (200..<300).contains(status) else {
                print("\(host) answered with HTTP status \(status); nothing saved.")
                return
            }
            guard data.count <= Self.sizeLimit else {
                print("\(host) sent \(data.count) bytes, more than the \(Self.sizeLimit >> 20) MB limit; nothing saved.")
                return
            }
            lock.lock()
            etags[text] = http?.value(forHTTPHeaderField: "ETag")
            lock.unlock()
            save(data, named: name)
        }.resume()
    }

    /// The link itself, made safe for a file name: "reports.example.com_patients_42".
    private func fileName(for url: URL, host: String) -> String {
        // The port is part of the name: the same path on another port is another server.
        var name = host + (url.port.map { ":\($0)" } ?? "") + url.path
        if let query = url.query, !query.isEmpty { name += "?" + query }
        while name.hasSuffix("/") { name.removeLast() }
        return String(name.map { $0.isLetter || $0.isNumber || $0 == "." || $0 == "-" ? $0 : "_" }.prefix(120))
    }

    private func savedFile(named name: String) -> URL? {
        ["json", "txt"].map { folder.appendingPathComponent("\(name).\($0)") }
            .first { FileManager.default.fileExists(atPath: $0.path) }
    }

    /// Makes `latest` a copy of the report just scanned, which may be an unchanged one seen before.
    private func makeLatest(_ data: Data, type: String) throws {
        let latest = folder.appendingPathComponent("latest.\(type)")
        if (try? Data(contentsOf: latest)) != data {
            try data.write(to: latest, options: .atomic)
        }
        try? FileManager.default.removeItem(at: folder.appendingPathComponent(type == "json" ? "latest.txt" : "latest.json"))
        if type == "json" { onReport?(data) }
    }

    private func save(_ data: Data, named name: String) {
        let json = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        let type = json == nil ? "txt" : "json"
        let file = folder.appendingPathComponent("\(name).\(type)")
        let previous = try? Data(contentsOf: file)
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            if previous != data {
                // The bytes exactly as they arrived, so nothing is lost by re-encoding.
                try data.write(to: file, options: .atomic)
            }
            try makeLatest(data, type: type)
        } catch {
            print("Couldn't save the report in \(folder.path): \(error.localizedDescription)")
            return
        }
        if previous == data {
            print("Report unchanged: \(file.path)")
            return
        }
        // Only the shape is printed: the contents may be personal.
        let shape: String
        switch json {
        case let object as [String: Any]: shape = "JSON object with keys: " + object.keys.sorted().joined(separator: ", ")
        case let array as [Any]: shape = "JSON array of \(array.count) items"
        case .some: shape = "a single JSON value"
        case .none: shape = "not JSON"
        }
        print("Report \(previous == nil ? "saved" : "updated"): \(file.path) (\(data.count) bytes, \(shape))")
    }
}

// MARK: - Medicines (what the FDA lists for a scanned barcode)

/// Looks up the barcode on a medicine package in the FDA's drug directory, through openFDA
/// (https://open.fda.gov/apis/drug/ndc), and saves what it lists.
///
/// A US medicine is identified by its NDC, a 10-digit code the FDA writes in three parts, as
/// 4-4-2, 5-3-2 or 5-4-1 digits ("0573-0150-40"). A package's barcode usually carries those
/// ten digits without the hyphens, after a "3": the retail barcode as 03 + NDC + check digit,
/// the GS1 codes on prescription packs as a 14-digit GTIN with one more digit in front. Not
/// every barcode does, though (some keep the code of a former owner), and the directory also
/// lists barcodes for some products. So one request asks for a product with this barcode or
/// with a package under any of the three ways of writing the ten digits.
///
/// The reports folder gets `medicine-<barcode>.json`, holding what was scanned, the
/// directory's record and a few lines of the drug's label, and `latest-medicine.json`, a copy
/// of the one scanned most recently. A saved answer is used again for a day (the directory
/// is updated daily), so scanning the same package again asks the FDA nothing.
///
/// openFDA allows 1,000 requests a day without a key. Set OPENFDA_API_KEY for more.
final class MedicineLookup {
    /// A barcode that stays near the camera drops in and out of view; don't act on it each time.
    private static let recheckAfter: TimeInterval = 10
    /// Most barcodes in view aren't medicines. One the FDA doesn't list isn't asked about again for a while.
    private static let retryMissAfter: TimeInterval = 600
    private static let keepFor: TimeInterval = 24 * 3600
    /// The parts of the label that are kept, each cut to this many characters.
    private static let labelParts = ["purpose", "indications_and_usage", "boxed_warning"]
    private static let labelLimit = 600

    /// Called with each medicine as it becomes the latest, whether looked up or read from its saved file.
    var onMedicine: ((Data) -> Void)?

    private let folder: URL
    private let session: URLSession
    private let apiKey = ProcessInfo.processInfo.environment["OPENFDA_API_KEY"]
    private let lock = NSLock()
    private var nextLook: [String: Date] = [:]

    init(folder: URL) {
        self.folder = folder
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 10
        configuration.timeoutIntervalForResource = 20
        session = URLSession(configuration: configuration)
    }

    /// What a barcode gives to search by.
    struct Key {
        /// The barcode's digits: what the saved file is named after.
        var barcode: String
        /// As the directory lists retail barcodes: 13 digits.
        var upc: String?
        /// The ten digits that may be an NDC.
        var ndcDigits: String?

        /// The three ways the FDA may write those ten digits.
        var ndcForms: [String] {
            guard let digits = ndcDigits.map(Array.init), digits.count == 10 else { return [] }
            return [[4, 4, 2], [5, 3, 2], [5, 4, 1]].map { lengths in
                var start = 0
                return lengths.map { length -> String in
                    defer { start += length }
                    return String(digits[start..<start + length])
                }.joined(separator: "-")
            }
        }
    }

    /// The digits to search by, or nil when the code isn't a product barcode.
    static func key(for code: DetectedCode) -> Key? {
        func isDigits(_ text: Substring) -> Bool { !text.isEmpty && text.allSatisfy { ("0"..."9").contains($0) } }
        switch code.kind {
        case "EAN13":
            // A 12-digit UPC-A, which is what US packages carry, is read as EAN-13 with a 0 in front.
            let digits = Substring(code.payload)
            guard digits.count == 13, isDigits(digits), hasValidCheckDigit(digits) else { return nil }
            return Key(barcode: code.payload, upc: code.payload,
                       ndcDigits: digits.hasPrefix("03") ? String(digits.dropFirst(2).prefix(10)) : nil)
        case "ITF14", "DataMatrix", "Code128", "GS1DataBar", "GS1DataBarExpanded", "GS1DataBarLimited":
            // GS1 codes: a 14-digit GTIN, on its own or after the application identifier "01",
            // which may be written "(01)" and may follow a symbology identifier such as "]d2".
            var text = Substring(code.payload)
            if text.hasPrefix("]") { text = text.dropFirst(3) }
            let lead = Substring(text.filter { $0 != "(" && $0 != ")" }.prefix { ("0"..."9").contains($0) })
            let gtin: Substring
            if lead.count == 14 {
                gtin = lead
            } else if lead.count >= 16, lead.hasPrefix("01") {
                gtin = lead.dropFirst(2).prefix(14)
            } else {
                return nil
            }
            guard hasValidCheckDigit(gtin) else { return nil }
            // A GTIN that starts with 0 is the retail barcode with a 0 in front; any other first
            // digit marks a case or bundle, whose check digit differs from the retail barcode's.
            let upc = gtin.hasPrefix("0") ? String(gtin.dropFirst()) : nil
            let ndcDigits = gtin.dropFirst().hasPrefix("03") ? String(gtin.dropFirst(3).prefix(10)) : nil
            guard upc != nil || ndcDigits != nil else { return nil }
            return Key(barcode: String(gtin), upc: upc, ndcDigits: ndcDigits)
        default:
            return nil
        }
    }

    /// The GS1 check: the last digit makes the weighted sum of the others a multiple of ten.
    static func hasValidCheckDigit(_ digits: Substring) -> Bool {
        let values = digits.compactMap(\.wholeNumberValue)
        guard values.count == digits.count, let check = values.last else { return false }
        let sum = values.dropLast().reversed().enumerated().reduce(0) { $0 + $1.element * ($1.offset % 2 == 0 ? 3 : 1) }
        return (10 - sum % 10) % 10 == check
    }

    func lookUp(_ code: DetectedCode) {
        guard let key = Self.key(for: code) else { return }
        lock.lock()
        let wait = Date() < (nextLook[key.barcode] ?? .distantPast)
        if !wait { nextLook[key.barcode] = Date().addingTimeInterval(Self.recheckAfter) }
        lock.unlock()
        guard !wait else { return }

        let file = folder.appendingPathComponent("medicine-\(key.barcode).json")
        if let saved = try? Data(contentsOf: file),
           let written = (try? FileManager.default.attributesOfItem(atPath: file.path))?[.modificationDate] as? Date,
           Date().timeIntervalSince(written) < Self.keepFor {
            print("Medicine already saved: \(file.path)")
            makeLatest(saved)
            return
        }

        var alternatives = key.ndcForms.map { "packaging.package_ndc:\"\($0)\"" }
        if let upc = key.upc { alternatives.insert("openfda.upc:\"\(upc)\"", at: 0) }
        print("Asking the FDA about \(code.kind) \(key.barcode)")
        get("ndc", search: alternatives.joined(separator: " OR "), limit: 10) { [self] answer, problem in
            guard let answer else {
                print("Couldn't look up \(key.barcode) at the FDA: \(problem)")
                return
            }
            let records = answer["results"] as? [[String: Any]] ?? []
            guard var record = records.first else {
                print("The FDA lists no medicine for \(code.kind) \(key.barcode).")
                lock.lock()
                nextLook[key.barcode] = Date().addingTimeInterval(Self.retryMissAfter)
                lock.unlock()
                return
            }
            // A record with this very package beats one that only shares the barcode.
            var package = ""
            search: for candidate in records {
                let packages = (candidate["packaging"] as? [[String: Any]] ?? []).compactMap { $0["package_ndc"] as? String }
                for form in key.ndcForms where packages.contains(form) {
                    record = candidate
                    package = form
                    break search
                }
            }
            var medicine: [String: Any] = [
                "barcode": key.barcode,
                "symbology": code.kind,
                "package_ndc": package,
                "listings": records.count,
                "fda_updated": ((answer["meta"] as? [String: Any])?["last_updated"] as? String) ?? "",
                "record": record,
            ]
            guard let product = record["product_ndc"] as? String else {
                save(medicine, to: file)
                return
            }
            // The label is a second request. The medicine is shown without it if it fails.
            get("label", search: "openfda.product_ndc:\"\(product)\"", limit: 1) { [self] answer, _ in
                if let label = (answer?["results"] as? [[String: Any]])?.first {
                    var kept: [String: Any] = [:]
                    for part in Self.labelParts {
                        if let text = (label[part] as? [String])?.joined(separator: " "), !text.isEmpty {
                            kept[part] = String(text.prefix(Self.labelLimit))
                        }
                    }
                    medicine["label"] = kept
                }
                save(medicine, to: file)
            }
        }
    }

    /// Calls back with the answer as JSON, or with nil and what went wrong.
    private func get(_ endpoint: String, search: String, limit: Int, then: @escaping ([String: Any]?, String) -> Void) {
        var parts = URLComponents(string: "https://api.fda.gov/drug/\(endpoint).json")!
        parts.queryItems = [URLQueryItem(name: "search", value: search), URLQueryItem(name: "limit", value: String(limit))]
        if let apiKey, !apiKey.isEmpty { parts.queryItems?.append(URLQueryItem(name: "api_key", value: apiKey)) }
        session.dataTask(with: parts.url!) { data, response, error in
            if let error {
                then(nil, error.localizedDescription)
                return
            }
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            let json = data.flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any]
            if status == 404 {
                then(["results": [] as [Any]], "")   // openFDA's way of saying nothing matched
            } else if status == 429 {
                then(nil, "the day's or minute's request limit is used up (set OPENFDA_API_KEY for a higher one)")
            } else if (200..<300).contains(status), let json {
                then(json, "")
            } else {
                then(nil, "HTTP status \(status)")
            }
        }.resume()
    }

    private func save(_ medicine: [String: Any], to file: URL) {
        // Keys in a fixed order, so the same medicine is the same bytes every time: the glasses
        // app tells a new medicine from the one already on its cards by comparing them.
        guard let data = try? JSONSerialization.data(withJSONObject: ["medicine": medicine],
                                                     options: [.sortedKeys, .prettyPrinted, .withoutEscapingSlashes]) else { return }
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try data.write(to: file, options: .atomic)
        } catch {
            print("Couldn't save the medicine in \(folder.path): \(error.localizedDescription)")
            return
        }
        let record = medicine["record"] as? [String: Any] ?? [:]
        let names = [record["brand_name"], record["generic_name"], medicine["package_ndc"]]
            .compactMap { $0 as? String }.filter { !$0.isEmpty }.joined(separator: ", ")
        print("Medicine saved: \(file.path) (\(names))")
        makeLatest(data)
    }

    private func makeLatest(_ data: Data) {
        let latest = folder.appendingPathComponent("latest-medicine.json")
        if (try? Data(contentsOf: latest)) != data {
            try? data.write(to: latest, options: .atomic)
        }
        onMedicine?(data)
    }
}

// MARK: - Sending reports to the glasses

/// Sends a scanned report to the app on the glasses, which shows the patient on its cards.
/// A medicine goes the same way, and takes the patient's place on the cards.
///
/// One TCP connection per report, with the report's JSON as the whole of what is sent. The
/// app answers with one line: "ok" or "error: ...". (The other end is ReportServer.java.)
/// Over USB instead of Wi-Fi: `adb forward tcp:32889 tcp:32889`, then `--glasses 127.0.0.1`.
final class GlassesLink {
    static let port: UInt16 = 32889
    private static let timeout: TimeInterval = 5

    private let queue = DispatchQueue(label: "glasses-link")
    private var lastOutcome = ""

    /// `what` is the word for it in the messages printed: "report" or "medicine".
    func send(_ report: Data, to host: String, as what: String = "report") {
        let connection = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: Self.port)!, using: .tcp)
        var finished = false
        // Everything below runs on `queue`, one step at a time.
        let finish: (String) -> Void = { [self] outcome in
            guard !finished else { return }
            finished = true
            connection.cancel()
            // A code that stays in view is sent again every few seconds: report each outcome once.
            let key = "\(report.hashValue) \(outcome)"
            if key != lastOutcome {
                lastOutcome = key
                print(outcome)
            }
        }
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                // The sending side stays open until the answer is in: closing it first makes
                // an "adb forward" drop the connection, answer and all. The app can tell where
                // the report ends without that.
                connection.send(content: report, completion: .contentProcessed { error in
                    if let error {
                        finish("Couldn't send the \(what) to the glasses at \(host): \(error.localizedDescription)")
                        return
                    }
                    connection.receive(minimumIncompleteLength: 1, maximumLength: 1024) { data, _, _, error in
                        let answer = data.flatMap { String(data: $0, encoding: .utf8) }?
                            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                        if answer == "ok" {
                            finish("\(what.prefix(1).uppercased() + what.dropFirst()) shown on the glasses at \(host).")
                        } else {
                            let reason = answer.isEmpty ? (error?.localizedDescription ?? "no answer") : answer
                            finish("The glasses at \(host) didn't take the \(what): \(reason)")
                        }
                    }
                })
            case .failed(let error), .waiting(let error):
                finish("Couldn't reach the glasses app at \(host), port \(Self.port): \(error.localizedDescription). Is the app open?")
            default:
                break
            }
        }
        connection.start(queue: queue)
        queue.asyncAfter(deadline: .now() + Self.timeout) {
            finish("The glasses app at \(host), port \(Self.port), didn't answer.")
        }
    }
}

// MARK: - Latest frame as a JPEG file

final class FrameSaver {
    private let url: URL
    private let interval: Double
    private let queue = DispatchQueue(label: "frame-saver")
    private let lock = NSLock()
    private var lastSave = Date.distantPast
    private var busy = false

    init(path: String, interval: Double) {
        url = URL(fileURLWithPath: path)
        self.interval = interval
    }

    func offer(_ pixelBuffer: CVPixelBuffer) {
        lock.lock()
        let due = !busy && Date().timeIntervalSince(lastSave) >= interval
        if due { busy = true; lastSave = Date() }
        lock.unlock()
        guard due else { return }
        queue.async { [self] in
            write(pixelBuffer)
            lock.lock(); busy = false; lock.unlock()
        }
    }

    private func write(_ pixelBuffer: CVPixelBuffer) {
        var image: CGImage?
        guard VTCreateCGImageFromCVPixelBuffer(pixelBuffer, options: nil, imageOut: &image) == noErr, let image else { return }
        // Write next to the target and rename, so a reader never sees half a file.
        let temporary = url.deletingLastPathComponent().appendingPathComponent(".\(url.lastPathComponent).tmp")
        guard let destination = CGImageDestinationCreateWithURL(temporary as CFURL, "public.jpeg" as CFString, 1, nil) else { return }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.85] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return }
        _ = rename(temporary.path, url.path)
    }
}

// MARK: - UDP

func localAddresses() -> [String] {
    var result: [String] = []
    var list: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&list) == 0 else { return [] }
    defer { freeifaddrs(list) }
    var cursor = list
    while let entry = cursor {
        defer { cursor = entry.pointee.ifa_next }
        let flags = Int32(entry.pointee.ifa_flags)
        guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0,
              let address = entry.pointee.ifa_addr, address.pointee.sa_family == sa_family_t(AF_INET) else { continue }
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        if getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
            result.append("\(String(cString: host)) (\(String(cString: entry.pointee.ifa_name)))")
        }
    }
    return result
}

func openSocket(port: UInt16) -> Int32 {
    let descriptor = socket(AF_INET, SOCK_DGRAM, 0)
    guard descriptor >= 0 else { fail("socket: \(String(cString: strerror(errno)))") }
    var yes: Int32 = 1
    setsockopt(descriptor, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
    // A 1080p keyframe arrives as one large, IP-fragmented datagram; leave room for a burst.
    var receiveBuffer: Int32 = 4 << 20
    setsockopt(descriptor, SOL_SOCKET, SO_RCVBUF, &receiveBuffer, socklen_t(MemoryLayout<Int32>.size))

    var address = sockaddr_in()
    address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
    address.sin_family = sa_family_t(AF_INET)
    address.sin_port = port.bigEndian
    address.sin_addr.s_addr = INADDR_ANY
    let bound = withUnsafePointer(to: &address) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(descriptor, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
    }
    guard bound == 0 else {
        fail("Can't listen on UDP port \(port): \(String(cString: strerror(errno))). Is another stream-viewer running?")
    }
    return descriptor
}

func receiveLoop(descriptor: Int32, stats: Stats, decoder: H264Decoder, dump: FileHandle?) {
    var buffer = [UInt8](repeating: 0, count: 65536)
    var warnedTypes = Set<UInt8>()
    while true {
        var from = sockaddr_in()
        var fromLength = socklen_t(MemoryLayout<sockaddr_in>.size)
        let received = buffer.withUnsafeMutableBytes { bytes in
            withUnsafeMutablePointer(to: &from) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    recvfrom(descriptor, bytes.baseAddress, bytes.count, 0, $0, &fromLength)
                }
            }
        }
        if received < 0 {
            if errno == EINTR { continue }
            fail("recvfrom: \(String(cString: strerror(errno)))")
        }
        guard received >= 1 else { continue }

        let sender = String(cString: inet_ntoa(from.sin_addr))
        if stats.packet(bytes: received, from: sender) {
            print("Receiving from \(sender)")
        }

        let type = buffer[0]
        switch type {
        case 0:
            let payload = Array(buffer[1..<received])
            dump?.write(Data(payload))
            decoder.feed(payload)
        case 1, 3:
            stats.otherPacket()   // audio / screenshot fragments: sent by the screen-capture mode, not the camera stream
        default:
            stats.otherPacket()
            if warnedTypes.insert(type).inserted {
                print("Ignoring packets of unknown type \(type).")
            }
        }
    }
}

// MARK: - Window

final class ViewerWindow: NSObject, NSApplicationDelegate, NSWindowDelegate {
    private let window: NSWindow
    private let videoLayer = CALayer()
    /// Outlines and labels of the codes in view, drawn over the video.
    private let codeLayer = CALayer()
    private let hint: NSTextField
    private var current: CVPixelBuffer?
    private var codesSeenAt = Date.distantPast

    init(port: UInt16) {
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 960, height: 720),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable],
                          backing: .buffered, defer: false)
        let addresses = localAddresses()
        let target = addresses.isEmpty ? "this Mac's Wi-Fi IP address" : addresses.joined(separator: "\n")
        hint = NSTextField(wrappingLabelWithString:
            "Waiting for video on UDP port \(port)\n\nIn MLDemo › Camera Stream, send the stream to:\n\(target)")
        super.init()

        let content = NSView(frame: window.contentLayoutRect)
        content.wantsLayer = true
        content.layer?.backgroundColor = NSColor.black.cgColor
        videoLayer.contentsGravity = .resizeAspect
        videoLayer.frame = content.bounds
        videoLayer.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
        content.layer?.addSublayer(videoLayer)
        codeLayer.frame = content.bounds
        codeLayer.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
        content.layer?.addSublayer(codeLayer)

        hint.alignment = .center
        hint.textColor = .white
        hint.font = .systemFont(ofSize: 18)
        hint.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(hint)
        NSLayoutConstraint.activate([
            hint.centerXAnchor.constraint(equalTo: content.centerXAnchor),
            hint.centerYAnchor.constraint(equalTo: content.centerYAnchor),
            hint.widthAnchor.constraint(lessThanOrEqualTo: content.widthAnchor, constant: -40),
        ])

        window.contentView = content
        window.title = "Glasses Stream"
        window.delegate = self
        window.center()
        window.makeKeyAndOrderFront(nil)
    }

    /// Call on the main thread.
    func show(_ pixelBuffer: CVPixelBuffer) {
        hint.isHidden = true
        current = pixelBuffer   // keeps the surface out of the decoder's pool while it's on screen
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        videoLayer.contents = CVPixelBufferGetIOSurface(pixelBuffer)?.takeUnretainedValue()
        CATransaction.commit()
    }

    /// Outlines the codes found in the latest scan. Call on the main thread.
    func show(codes: [DetectedCode]) {
        if codes.isEmpty {
            // A code often goes unread for a frame or two; keep its outline rather than flicker.
            guard Date().timeIntervalSince(codesSeenAt) > 0.4 else { return }
        } else {
            codesSeenAt = Date()
        }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        codeLayer.sublayers?.forEach { $0.removeFromSuperlayer() }
        if let picture = pictureRect() {
            for code in codes { draw(code, in: picture) }
        }
        CATransaction.commit()
    }

    /// Where the video sits inside the window: scaled to fit and centred, like `videoLayer` draws it.
    private func pictureRect() -> CGRect? {
        guard let current else { return nil }
        let width = CGFloat(CVPixelBufferGetWidth(current)), height = CGFloat(CVPixelBufferGetHeight(current))
        let bounds = codeLayer.bounds
        guard width > 0, height > 0, bounds.width > 0, bounds.height > 0 else { return nil }
        let scale = min(bounds.width / width, bounds.height / height)
        let size = CGSize(width: width * scale, height: height * scale)
        return CGRect(x: bounds.midX - size.width / 2, y: bounds.midY - size.height / 2, width: size.width, height: size.height)
    }

    private func draw(_ code: DetectedCode, in picture: CGRect) {
        // Both Vision and this layer measure from the bottom-left corner.
        let points = code.corners.map {
            CGPoint(x: picture.minX + $0.x * picture.width, y: picture.minY + $0.y * picture.height)
        }
        let path = CGMutablePath()
        path.addLines(between: points)
        path.closeSubpath()
        let outline = CAShapeLayer()
        outline.path = path
        outline.strokeColor = NSColor.systemGreen.cgColor
        outline.fillColor = NSColor.systemGreen.withAlphaComponent(0.15).cgColor
        outline.lineWidth = 3
        outline.lineJoin = .round
        codeLayer.addSublayer(outline)

        var text = code.payload.replacingOccurrences(of: "\n", with: " ")
        if text.count > 80 { text = text.prefix(80) + "…" }
        text = "\(code.kind)  \(text)"
        let font = NSFont.systemFont(ofSize: 15, weight: .semibold)
        let textSize = (text as NSString).size(withAttributes: [.font: font])
        let size = CGSize(width: ceil(textSize.width) + 12, height: ceil(textSize.height) + 4)
        let box = path.boundingBoxOfPath
        // Above the code, or below it when there's no room; never off the side of the window.
        var origin = CGPoint(x: box.minX, y: box.maxY + 6)
        if origin.y + size.height > codeLayer.bounds.maxY { origin.y = box.minY - 6 - size.height }
        origin.x = max(0, min(origin.x, codeLayer.bounds.maxX - size.width))

        let label = CATextLayer()
        label.string = NSAttributedString(string: text, attributes: [.font: font, .foregroundColor: NSColor.white])
        label.alignmentMode = .center
        label.backgroundColor = NSColor.black.withAlphaComponent(0.75).cgColor
        label.cornerRadius = 4
        label.contentsScale = window.backingScaleFactor
        label.frame = CGRect(origin: origin, size: size)
        codeLayer.addSublayer(label)
    }

    func setTitle(_ title: String) { window.title = title }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
}

// MARK: - Main

setvbuf(stdout, nil, _IOLBF, 0)
let options = parseOptions()
let stats = Stats()
let decoder = H264Decoder()
let saver = options.framePath.map { FrameSaver(path: $0, interval: options.saveInterval) }

var dump: FileHandle?
if let path = options.dumpPath {
    guard FileManager.default.createFile(atPath: path, contents: nil), let handle = FileHandle(forWritingAtPath: path) else {
        fail("Can't write to \(path)")
    }
    dump = handle
}

let descriptor = openSocket(port: options.port)
print("Listening on UDP port \(options.port).")
let addresses = localAddresses()
if !addresses.isEmpty {
    print("Send the glasses' stream to one of: \(addresses.joined(separator: ", "))")
}

var viewer: ViewerWindow?
if options.window {
    let app = NSApplication.shared
    app.setActivationPolicy(.regular)
    let menu = NSMenu()
    let appItem = NSMenuItem()
    appItem.submenu = NSMenu()
    appItem.submenu?.addItem(withTitle: "Quit Stream Viewer", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
    menu.addItem(appItem)
    app.mainMenu = menu
    viewer = ViewerWindow(port: options.port)
    app.delegate = viewer
    app.activate(ignoringOtherApps: true)
}

// QR codes hold the links to patient reports. The rest are the barcodes medicine packages carry:
// the retail barcode (UPC-A, read as EAN-13) and the GS1 codes on prescription packs.
let medicineCodes: [VNBarcodeSymbology] = [.ean13, .dataMatrix, .code128, .itf14,
                                           .gs1DataBar, .gs1DataBarExpanded, .gs1DataBarLimited]
let detector = options.detect
    ? CodeDetector(symbologies: options.barcodes ? nil : [.qr] + (options.medicines ? medicineCodes : []))
    : nil
let codeLog = CodeLog()
let reportsFolder = options.reportsPath.map { URL(fileURLWithPath: $0) }
    ?? URL(fileURLWithPath: CommandLine.arguments[0]).resolvingSymlinksInPath()
        .deletingLastPathComponent().appendingPathComponent("reports")
let link = options.send ? GlassesLink() : nil
/// Sends a report or a medicine to the glasses app, if there is anywhere to send it.
func sendToGlasses(_ data: Data, as what: String) {
    // The glasses are wherever the stream comes from, unless --glasses says otherwise.
    let host = options.glassesHost ?? stats.streamSource
    if !host.isEmpty { link?.send(data, to: host, as: what) }
}
var fetcher: ReportFetcher?
if options.detect && options.fetch {
    fetcher = ReportFetcher(folder: reportsFolder, onlyHost: options.onlyHost)
    fetcher?.onReport = { sendToGlasses($0, as: "report") }
    print("Links in scanned codes are fetched and saved in \(reportsFolder.path)")
}
var medicines: MedicineLookup?
if options.detect && options.medicines {
    medicines = MedicineLookup(folder: reportsFolder)
    medicines?.onMedicine = { sendToGlasses($0, as: "medicine") }
    print("Medicine barcodes are looked up at the FDA and saved in \(reportsFolder.path)")
}
if link != nil && (fetcher != nil || medicines != nil) {
    print("JSON reports and medicines are also sent to the glasses app at \(options.glassesHost ?? "the stream's address"), port \(GlassesLink.port)")
}
detector?.onCodes = { codes in
    for code in codeLog.note(codes) {
        fetcher?.fetch(code.payload)
        medicines?.lookUp(code)
    }
    if let viewer {
        DispatchQueue.main.async { viewer.show(codes: codes) }
    }
}

decoder.onError = { stats.decodeError() }
decoder.onFrame = { pixelBuffer in
    let size = CGSize(width: CVPixelBufferGetWidth(pixelBuffer), height: CVPixelBufferGetHeight(pixelBuffer))
    let total = stats.frame(size)
    saver?.offer(pixelBuffer)
    detector?.offer(pixelBuffer)
    if let viewer {
        DispatchQueue.main.async { viewer.show(pixelBuffer) }
    }
    if let limit = options.quitAfter, total >= limit {
        print("Decoded \(total) frames; quitting.")
        Thread.sleep(forTimeInterval: 0.3)   // let a pending --save-frame finish
        exit(0)
    }
}

let receiver = Thread { receiveLoop(descriptor: descriptor, stats: stats, decoder: decoder, dump: dump) }
receiver.name = "udp-receiver"
receiver.start()

var hadVideo = false
let ticker = Timer(timeInterval: 1, repeats: true) { _ in
    let snapshot = stats.take()
    let title: String
    if snapshot.frames > 0 {
        hadVideo = true
        let megabits = Double(snapshot.bytes) * 8 / 1_000_000
        let description = "\(Int(snapshot.size.width))x\(Int(snapshot.size.height)) · \(snapshot.frames) fps · "
            + String(format: "%.1f Mbps", megabits)
        var line = description + " · from \(snapshot.sender)"
        if snapshot.decodeErrors > 0 { line += " · \(snapshot.decodeErrors) undecodable" }
        print(line)
        title = "Glasses Stream — " + description
    } else if snapshot.packets > 0 {
        print("\(snapshot.packets) packets from \(snapshot.sender), but no decodable video yet (waiting for a keyframe).")
        title = "Glasses Stream — waiting for a keyframe"
    } else {
        title = hadVideo ? "Glasses Stream — no data" : "Glasses Stream"
    }
    viewer?.setTitle(title)
}
RunLoop.main.add(ticker, forMode: .common)

if options.window {
    NSApplication.shared.run()
} else {
    RunLoop.main.run()
}
