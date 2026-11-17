// Zenlygram alternate launcher icon renderer (CoreGraphics). Run with: xcrun swift zg_alt_render.swift <art.png> <outDir>
//
// Input: one iOS alternate icon (Telegram/Telegram-iOS/Z<Name>.alticon/Z<Name>@3x.png, an opaque full-bleed 180px
// square). Writes high-resolution masters that make_alt_icons.sh downsizes with sips:
//   fg.png            adaptive-icon foreground: 108dp canvas, the art at 68% of the canvas (like the default icon's
//                     zg_render.swift), transparent around it. The art covers the whole 72dp viewport, so the
//                     solid background layer only shows during launcher parallax.
//   legacy.png        API 23-25 square icon: 48dp canvas, 40dp rounded square (8dp corners) with the art.
//   legacy_round.png  API 25 round icon: 48dp canvas, 44dp disc with the art.
// Prints "#RRGGBB": the average colour of the art's outer border, used for the background layer.
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let args = CommandLine.arguments
guard args.count == 3 else {
    FileHandle.standardError.write("usage: zg_alt_render.swift <art.png> <outDir>\n".data(using: .utf8)!)
    exit(2)
}
let outDir = URL(fileURLWithPath: args[2], isDirectory: true)
let srgb = CGColorSpace(name: CGColorSpace.sRGB)!

func load(_ path: String) -> CGImage {
    guard let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
          let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else {
        FileHandle.standardError.write("cannot read \(path)\n".data(using: .utf8)!)
        exit(1)
    }
    return img
}

func context(_ w: Int, _ h: Int) -> CGContext {
    let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: srgb,
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    ctx.interpolationQuality = .high
    ctx.setShouldAntialias(true)
    return ctx
}

func save(_ img: CGImage, _ name: String) {
    let url = outDir.appendingPathComponent(name)
    let dst = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(dst, img, nil)
    if !CGImageDestinationFinalize(dst) {
        FileHandle.standardError.write("cannot write \(url.path)\n".data(using: .utf8)!)
        exit(1)
    }
}

let art = load(args[1])
let A = Double(art.width)

// Border colour: average of the outermost 3px ring.
do {
    let w = art.width, h = art.height
    let ctx = context(w, h)
    ctx.draw(art, in: CGRect(x: 0, y: 0, width: w, height: h))
    let p = ctx.data!.bindMemory(to: UInt8.self, capacity: ctx.bytesPerRow * h)
    var r = 0.0, g = 0.0, b = 0.0, n = 0.0
    for y in 0..<h {
        for x in 0..<w where x < 3 || y < 3 || x >= w - 3 || y >= h - 3 {
            let i = y * ctx.bytesPerRow + x * 4
            r += Double(p[i]); g += Double(p[i + 1]); b += Double(p[i + 2]); n += 1
        }
    }
    print(String(format: "#%02X%02X%02X", Int((r / n).rounded()), Int((g / n).rounded()), Int((b / n).rounded())))
}

// Adaptive foreground: art = 68% of the 108dp canvas.
do {
    let canvas = Int((A / 0.68).rounded())
    let ctx = context(canvas, canvas)
    let o = (Double(canvas) - A) / 2
    ctx.draw(art, in: CGRect(x: o, y: o, width: A, height: A))
    save(ctx.makeImage()!, "fg.png")
}

// Shape + art, transparent outside the (antialiased) shape. shapeFraction = shape size / canvas size.
func shaped(_ name: String, shapeFraction: Double, cornerFraction: Double?) {
    let canvas = Int((A / shapeFraction).rounded())
    let ctx = context(canvas, canvas)
    let o = (Double(canvas) - A) / 2
    let rect = CGRect(x: o, y: o, width: A, height: A)
    let path: CGPath
    if let c = cornerFraction {
        path = CGPath(roundedRect: rect, cornerWidth: A * c, cornerHeight: A * c, transform: nil)
    } else {
        path = CGPath(ellipseIn: rect, transform: nil)
    }
    ctx.addPath(path)
    ctx.setFillColor(CGColor(red: 0, green: 0, blue: 0, alpha: 1))
    ctx.fillPath()
    ctx.setBlendMode(.sourceAtop)
    ctx.draw(art, in: rect)
    save(ctx.makeImage()!, name)
}

shaped("legacy.png", shapeFraction: 40.0 / 48.0, cornerFraction: 8.0 / 40.0)
shaped("legacy_round.png", shapeFraction: 44.0 / 48.0, cornerFraction: nil)
