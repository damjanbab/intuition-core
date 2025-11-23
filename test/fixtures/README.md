# Test Fixtures

Quarantined catalog entries that should not live in `resources/dictionary`:
- `code_types_test_fixtures.edn` holds generator samples used by codetype generation error-path tests.
- `codetype_inference_sample.edn` preserves the old placeholder file for reference only.

These fixtures keep the live catalog limited to governed CodeTypes (§§2.1–2.2, §§3.3–3.6, §4.7, §5, §8.1, §9, §11) while still supporting negative-path coverage.
