import CoreGraphics

/// Corner radii. The first three are the scale every Mac app of mine shares:
/// rows, small boxes and icons use `control`, notices `banner`, large areas like a drop zone `card`.
enum Radius {
    static let control: CGFloat = 8
    static let banner: CGFloat = 12
    static let card: CGFloat = 16

    // UnbloatMyTV's own.
    /// An app's banner in the list: TV apps show a wide 16:9 picture, not a square icon.
    static let appBanner: CGFloat = 6
    /// The TV's screen in the remote.
    static let screen: CGFloat = 10
}
