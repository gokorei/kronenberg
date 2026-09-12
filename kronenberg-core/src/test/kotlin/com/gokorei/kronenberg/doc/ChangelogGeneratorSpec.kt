package com.gokorei.kronenberg.doc

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class ChangelogGeneratorSpec {
    private val generator: ChangelogGenerator = DefaultChangelogGenerator()

    @Test
    fun `generates valid Keep a Changelog document from release notes text`() {
        val sampleReleaseNotes =
            """
            # Release Notes

            ---

            ## Next

            ### New Features
            - Upcoming feature X.

            ### Bug Fixes
            - Fix edge case in mutator Y.

            ---

            ## v0.1.0 — 2026-09-12

            ### New Features
            - Initial release of Kronenberg AST mutation engine.

            ### Improvements
            - Virtual thread sandbox execution.

            ### Bug Fixes
            - Range operator syntax whitespace fix.
            """.trimIndent()

        val changelog = generator.generateFromReleaseNotes(sampleReleaseNotes, repoUrl = "https://github.com/gokorei/kronenberg")

        changelog shouldContain "# Changelog"
        changelog shouldContain "## [Unreleased]"
        changelog shouldContain "### Added"
        changelog shouldContain "- Upcoming feature X."
        changelog shouldContain "### Fixed"
        changelog shouldContain "- Fix edge case in mutator Y."
        changelog shouldContain "## [0.1.0] - 2026-09-12"
        changelog shouldContain "- Initial release of Kronenberg AST mutation engine."
        changelog shouldContain "### Changed"
        changelog shouldContain "- Virtual thread sandbox execution."
        changelog shouldContain "[Unreleased]: https://github.com/gokorei/kronenberg/compare/v0.1.0...HEAD"
        changelog shouldContain "[0.1.0]: https://github.com/gokorei/kronenberg/releases/tag/v0.1.0"
    }

    @Test
    fun `handles multiple released versions with comparative range links`() {
        val notes =
            """
            ## v0.2.0 — 2026-10-01
            ### New Features
            - Feature 2.

            ## v0.1.0 — 2026-09-12
            ### New Features
            - Feature 1.
            """.trimIndent()

        val changelog = generator.generateFromReleaseNotes(notes, repoUrl = "https://github.com/gokorei/kronenberg")

        changelog shouldContain "## [0.2.0] - 2026-10-01"
        changelog shouldContain "## [0.1.0] - 2026-09-12"
        changelog shouldContain "[0.2.0]: https://github.com/gokorei/kronenberg/compare/v0.1.0...v0.2.0"
        changelog shouldContain "[0.1.0]: https://github.com/gokorei/kronenberg/releases/tag/v0.1.0"
    }
}
