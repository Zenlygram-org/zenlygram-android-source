// Zenlygram launcher art renderer (CoreGraphics). Run with: xcrun swift zg_render.swift <signature_1024.png> <outDir>
//
// Input: Telegram/Telegram-iOS/Zenlygram.icon/Assets/signature_1024.png from the iOS repo (blue Z on a near-black,
// slightly noisy square). Writes high-resolution masters that make_icons.sh downsizes with sips:
//   fg.png          adaptive-icon foreground: 108dp canvas, the art at 68% of the canvas (the Z, ~64% of the art,
//                   then fits the 66dp safe zone), transparent around it. Pairs with a pure black background layer.
//   legacy.png      API 23-25 square icon: 48dp canvas, 40dp black rounded square (8dp corners) with the art.
//   legacy_round.png API 25 round icon: 48dp canvas, 44dp black disc with the art.
//   disc.png        full-bleed disc (account/contact/person icon, Terms of Service logo, intro sphere texture).
//
// The art is "cleaned" first: the background noise (max channel <= 18 of 255) is faded out and the result is turned
// into premultiplied RGBA whose composite over black equals the cleaned art, so there is no visible square edge on the
// black background layer and the Z body stays opaque.
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let args = CommandLine.arguments
guard args.count == 3 else {
    FileHandle.standardError.write("usage: zg_render.swift <signature_1024.png> <outDir>\n".data(using: .utf8)!)
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
    print("wrote \(url.path) \(img.width)x\(img.height)")
}

func smoothstep(_ e0: Double, _ e1: Double, _ x: Double) -> Double {
    let t = min(max((x - e0) / (e1 - e0), 0), 1)
    return t * t * (3 - 2 * t)
}

// Cleaned, alpha-extracted art at the master's own size.
func cleanArt(_ master: CGImage) -> CGImage {
    let w = master.width, h = master.height
    let ctx = context(w, h)
    ctx.setFillColor(CGColor(red: 0, green: 0, blue: 0, alpha: 1))
    ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
    ctx.draw(master, in: CGRect(x: 0, y: 0, width: w, height: h))
    let p = ctx.data!.bindMemory(to: UInt8.self, capacity: ctx.bytesPerRow * h)
    for y in 0..<h {
        for x in 0..<w {
            let i = y * ctx.bytesPerRow + x * 4
            let r = Double(p[i]), g = Double(p[i + 1]), b = Double(p[i + 2])
            let weight = smoothstep(18, 40, max(r, g, b))
            let cr = r * weight, cg = g * weight, cb = b * weight
            let alpha = min(255.0, max(cr, cg, cb) / 0.75)   // >= every channel, so premultiplied stays valid
            p[i] = UInt8(cr.rounded()); p[i + 1] = UInt8(cg.rounded()); p[i + 2] = UInt8(cb.rounded())
            p[i + 3] = UInt8(max(alpha.rounded(), max(cr, cg, cb).rounded()))
        }
    }
    return ctx.makeImage()!
}

let master = load(args[1])
let art = cleanArt(master)
let A = Double(art.width)   // the art is always drawn 1:1; canvases are sized around it, sips does the downsizing

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
    ctx.setBlendMode(.sourceAtop)   // keep the shape's (antialiased) alpha, paint the art inside it
    ctx.draw(art, in: rect)
    save(ctx.makeImage()!, name)
}

shaped("legacy.png", shapeFraction: 40.0 / 48.0, cornerFraction: 8.0 / 40.0)
shaped("legacy_round.png", shapeFraction: 44.0 / 48.0, cornerFraction: nil)
shaped("disc.png", shapeFraction: 1.0, cornerFraction: nil)
