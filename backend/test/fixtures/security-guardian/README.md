# Security Guardian fixtures

`benign/` holds code that looks like the shapes the rules hunt for and must not be reported: a parse-time
`new URL(path, "http://internal.invalid")` base, enum keys whose value repeats the key name, SQL interpolated only
after a closed-set check, `spawnSync` with an argument array, 0600 snapshot modes, and a launcher-only Android
manifest. `test/security-guardian.test.js` scans this directory and asserts zero findings, which is what keeps the
rules honest about the code this repository actually contains.

True positives are not stored here. A file containing a realistic-looking credential would itself be the pattern the
scanner reports, so the test writes those sources into a temporary directory at run time and asserts each rule fires
there, then deletes it.
