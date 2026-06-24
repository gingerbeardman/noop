import XCTest
@testable import StrandAnalytics

final class BatteryEstimatorTests: XCTestCase {

    private let h = 3600

    func testNilWhenNoSamples() {
        XCTAssertNil(BatteryEstimator.estimate(samples: [], ratedHours: BatteryEstimator.ratedLifeHoursWhoop5))
    }

    func testMeasuredRateFromCleanDischarge() {
        // 100% to 90% over 10h is 1 %/h; at 90% that leaves 90h, from the user's own discharge.
        let e = BatteryEstimator.estimate(samples: [(0, 100), (10 * h, 90)],
                                          ratedHours: BatteryEstimator.ratedLifeHoursWhoop5)!
        XCTAssertEqual(e.source, .measured)
        XCTAssertEqual(e.remainingHours, 90, accuracy: 1e-6)
        XCTAssertEqual(e.hoursRemaining, 90, accuracy: 1e-6)
        XCTAssertEqual(e.daysRemaining, 90.0 / 24, accuracy: 1e-6)
        XCTAssertEqual(e.currentSoc, 90, accuracy: 1e-6)
    }

    func testRatedFallbackWhenSpanTooShort() {
        // A single reading has no span to fit, so it falls back to rated: 50 / (100/108) = 54h.
        let e = BatteryEstimator.estimate(samples: [(0, 50)],
                                          ratedHours: BatteryEstimator.ratedLifeHoursWhoop4)!
        XCTAssertEqual(e.source, .rated)
        XCTAssertEqual(e.remainingHours, 54, accuracy: 1e-6)
    }

    func testChargeRestartsTheDischargeRun() {
        // Discharge 100->70, then a charge back to 100, then 100->88 over 6h. The rate is fit on the
        // post-charge segment only (2 %/h), never across the charge.
        let e = BatteryEstimator.estimate(samples: [(0, 100), (4 * h, 70), (5 * h, 100), (11 * h, 88)],
                                          ratedHours: BatteryEstimator.ratedLifeHoursWhoop5)!
        XCTAssertEqual(e.source, .measured)
        XCTAssertEqual(e.remainingHours, 44, accuracy: 1e-6)   // 88 / 2
    }

    func testRatedFallbackWhenDropTooSmall() {
        // 100->99 over 10h is a 1% drop, under minDropPct(2), so it falls back to rated instead of
        // reporting a wild ~1000h. The estimate stays anchored to the latest SoC.
        let e = BatteryEstimator.estimate(samples: [(0, 100), (10 * h, 99)],
                                          ratedHours: BatteryEstimator.ratedLifeHoursWhoop5)!
        XCTAssertEqual(e.source, .rated)
        XCTAssertEqual(e.remainingHours, 285.12, accuracy: 1e-6)   // 99 / (100/288)
    }

    func testClampsToOneAndAHalfTimesRated() {
        // A slow drain near full charge must not report more than 1.5x the rated life. 100% to 90% over
        // 20h is 0.5 %/h, current 90% -> 180h raw, clamped to 108*1.5 = 162h.
        let e = BatteryEstimator.estimate(samples: [(0, 100), (20 * h, 90)],
                                          ratedHours: BatteryEstimator.ratedLifeHoursWhoop4)!
        XCTAssertEqual(e.source, .measured)
        XCTAssertEqual(e.remainingHours, 162, accuracy: 1e-6)   // clamped, not 200
    }

    func testUnsortedSamplesAreHandled() {
        // Same two points as the clean-discharge case but out of order: result must match.
        let e = BatteryEstimator.estimate(samples: [(10 * h, 90), (0, 100)],
                                          ratedHours: BatteryEstimator.ratedLifeHoursWhoop5)!
        XCTAssertEqual(e.source, .measured)
        XCTAssertEqual(e.remainingHours, 90, accuracy: 1e-6)
        XCTAssertEqual(e.currentSoc, 90, accuracy: 1e-6)
    }

    func testLabelSwitchesHoursToDaysAt48h() {
        XCTAssertEqual(BatteryEstimator.label(hours: 14), "~14h")
        XCTAssertEqual(BatteryEstimator.label(hours: 108), "~4.5 days")
    }
}
