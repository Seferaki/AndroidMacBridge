// swift-tools-version: 5.9
import PackageDescription
let package = Package(
    name: "SamsungMacBridge", platforms: [.macOS(.v13)],
    products: [
        .library(name: "BridgeCore", targets: ["BridgeCore"]),
        .executable(name: "BridgeMac", targets: ["BridgeMac"]),
        .executable(name: "BridgeHarness", targets: ["BridgeHarness"])
    ],
    targets: [
        .target(name: "BridgeCore"),
        .executableTarget(name: "BridgeMac", dependencies: ["BridgeCore"]),
        .executableTarget(name: "BridgeHarness", dependencies: ["BridgeCore"]),
        .testTarget(name: "BridgeCoreTests", dependencies: ["BridgeCore"])
    ]
)
