import XCTest
@testable import Strand

/// Pins the suggestion catalogue (#714): the two new indoor presets exist, are spelled byte-for-byte
/// the way Android persists them (the stored sport label round-trips cross-platform via CSV / export),
/// and default GPS off (no route on a treadmill or a lifting session). Mirrors the Android
/// WorkoutSportTest intent for the same two sports.
final class WorkoutCatalogTests: XCTestCase {

    func testTreadmillWalkPresetExistsWithGpsOff() {
        let s = WorkoutCatalog.sport(named: "Treadmill walk")
        XCTAssertNotNil(s, "Treadmill walk must be in the suggestion catalogue (#714)")
        XCTAssertEqual(s?.name, "Treadmill walk", "Name is persisted data, must match Android byte-for-byte")
        XCTAssertEqual(s?.isDistanceSport, false, "Indoor walk has no route, so GPS defaults off")
    }

    func testBodybuildingPresetExistsWithGpsOff() {
        let s = WorkoutCatalog.sport(named: "Bodybuilding")
        XCTAssertNotNil(s, "Bodybuilding must be in the suggestion catalogue (#714)")
        XCTAssertEqual(s?.name, "Bodybuilding", "Name is persisted data, must match Android byte-for-byte")
        XCTAssertEqual(s?.isDistanceSport, false, "A lifting session has no route, so GPS defaults off")
    }

    func testLookupIsCaseInsensitive() {
        XCTAssertEqual(WorkoutCatalog.sport(named: "treadmill WALK")?.name, "Treadmill walk")
        XCTAssertEqual(WorkoutCatalog.sport(named: "  bodybuilding  ")?.name, "Bodybuilding")
    }
}
