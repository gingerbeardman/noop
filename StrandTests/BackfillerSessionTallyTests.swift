import XCTest
@testable import Strand

/// Pins the success-side observability the log forensics flagged as the blind spot (#150): NOOP logged
/// FAILURES (decoded-to-0) but never SUCCESSES, so a strap log couldn't tell a banking strap from a
/// broken one. These cover the pure tally + summary helpers that drive the new
/// "Backfill: session persisted N rows (M with motion) across K night(s)" line.
final class BackfillerSessionTallyTests: XCTestCase {

    // rows = biometric streams only (HR, R-R, SpO2, skin-temp, resp, gravity) — battery/events are
    // housekeeping, NOT biometric history, so they must not inflate the count. motion = gravity.
    func testChunkTallySumsBiometricRowsAndGravityOnly() {
        let counts = (hr: 10, rr: 4, events: 99, battery: 7, spo2: 3, skinTemp: 2, resp: 1, gravity: 5)
        let tally = Backfiller.chunkTally(counts: counts, timestamps: [])
        XCTAssertEqual(tally.rows, 10 + 4 + 3 + 2 + 1 + 5)   // 25 — events(99)/battery(7) excluded
        XCTAssertEqual(tally.motion, 5)
        XCTAssertTrue(tally.nights.isEmpty)
    }

    // nights collapse timestamps to distinct day-keys (ts / 86400), so a chunk spanning a day boundary
    // counts two nights and same-day samples count once.
    func testChunkTallyNightsAreDistinctDayKeys() {
        let day0 = 1_700_000_000
        let sameDay = day0 + 3_600
        let nextDay = day0 + 86_400
        let tally = Backfiller.chunkTally(counts: (0, 0, 0, 0, 0, 0, 0, 0), timestamps: [day0, sameDay, nextDay])
        XCTAssertEqual(tally.nights, Set([day0 / 86_400, nextDay / 86_400]))
        XCTAssertEqual(tally.nights.count, 2)
    }

    // The summary stays SILENT when nothing persisted, so a console-only / caught-up session doesn't
    // claim a false success — the existing empty-banking diagnostics speak for that case instead.
    func testSessionSummaryNilWhenNoRows() {
        XCTAssertNil(Backfiller.sessionSummaryLine(rows: 0, motion: 0, skinTemp: 0, nights: 0))
    }

    func testSessionSummaryFormat() {
        XCTAssertEqual(
            Backfiller.sessionSummaryLine(rows: 240, motion: 180, skinTemp: 12, nights: 3),
            "Backfill: session persisted 240 rows (180 with motion, 12 skin-temp) across 3 night(s).")
    }

    // #727: a strap banking HR/RR-only records (no DSP sleep block) persists rows but ZERO skin-temp,
    // so the line surfaces that 0 and "skin temp never appears" reports are self-diagnosing from the log.
    func testSessionSummaryShowsZeroSkinTemp() {
        XCTAssertEqual(
            Backfiller.sessionSummaryLine(rows: 872, motion: 172, skinTemp: 0, nights: 1),
            "Backfill: session persisted 872 rows (172 with motion, 0 skin-temp) across 1 night(s).")
    }
}
