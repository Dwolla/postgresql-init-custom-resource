### Project-specific development guidelines

This document summarizes non-obvious, project-specific details to help advanced contributors build, test, and debug this repo reliably.

#### Build and configuration
- Toolchain
  - Scala 3.7.4 (see `build.sbt`), JVM 17 (GitHub CI uses Temurin 17). Use Java 17 locally to match.
  - Build tool: sbt (standard layout). Packaging uses `sbt-native-packager` (`JavaAppPackaging` and `UniversalPlugin`).
  - Codegen: The `smithy` subproject is enabled with `com.disneystreaming.smithy4s` codegen. sbt will generate code for Smithy models before compiling dependents. No manual steps are needed beyond running sbt.

- Subprojects
  - Root project is named `postgresql-init-core` in `build.sbt` and depends on `:smithy` for generated Smithy4s code.
  - Smithy models live under `smithy/src/main`.

- Dependencies and noteworthy versions
  - Skunk 0.6.4 for PostgreSQL access.
  - Cats Effect 3 ecosystem, Natchez for tracing (including X-Ray), `log4cats` + `log4j2` for logging.
  - Test stack: MUnit 1.2.x, `munit-scalacheck`, and `discipline-munit`.

- Serverless packaging and deploy (optional, for AWS publication)
  - `serverless.yml` is configured to deploy two Lambda artifacts. `sbt deploy` shells out to `serverless deploy`.
  - Required environment for deployment (see README): `BUCKET`, `ACCOUNT`, `STAGE`, `SUBNET_ID`, `SECURITY_GROUP`.
  - sbt will pass `DATABASE_ARTIFACT_PATH` to Serverless at deploy-time.
  - Local deploy command string is configurable via `serverlessDeployCommand` in `build.sbt`.

- Local manual invocation for development
  - `src/test/scala/com/dwolla/postgres/init/LocalApp.scala` contains a small `IOApp` for running the handler locally against a real Postgres instance and real AWS Secrets Manager. This is for integration/manual testing; it requires valid AWS credentials, an accessible DB, and OTel environment if you want traces.

- Network/SSL conventions for DB connections
  - `CreateSkunkSession` chooses `SSL.None` for `localhost` and `SSL.System` otherwise, and picks `postgres` as the default database unless a specific `Database` is provided. This is relevant when simulating local connections.

#### Testing: how to run, add, and scope tests
- Running all tests
  - From project root: `sbt test`. This builds the Smithy subproject if needed, compiles tests, and runs all MUnit suites under `src/test/scala`.

- Running a single suite or test
  - Single suite: `sbt "testOnly com.dwolla.postgres.init.ExtractRequestPropertiesSpec"`
  - Single test within a suite: `sbt "testOnly com.dwolla.postgres.init.SqlStringRegexSpec -- *passwords*"` (MUnit supports test filters via `--` followed by a glob pattern matching the test name.)

- Property-based and law checks
  - The project uses ScalaCheck and Discipline. Example: `SqlStringRegexSpec` combines Arbitrary instances with `checkAll` law tests (`SemigroupTests[SqlIdentifier]`). If you add new `cats` typeclass instances, consider adding law checks here.

- Example: adding and running a simple test (demonstrated and verified)
  - A trivial MUnit suite named `DemoSanitySpec` was added temporarily to verify commands:
    ```scala
    package com.dwolla.postgres.init

    class DemoSanitySpec extends munit.FunSuite {
      test("demo sanity: 2 + 2 == 4") {
        assertEquals(2 + 2, 4)
      }
    }
    ```
  - Run it directly: `sbt "testOnly com.dwolla.postgres.init.DemoSanitySpec"`.
  - It passed locally; the file has been removed to keep the repo clean.

- Adding new tests
  - Place suites under `src/test/scala` using package `com.dwolla.postgres.init` (match sources if testing package-private APIs).
  - Use `munit.FunSuite` for example-based tests, and add `munit-scalacheck` for generators/properties. For typeclass laws, add `DisciplineSuite` + `discipline-munit`.
  - Prefer deterministic unit tests. For DB integration, use `LocalApp` or an ephemeral containerized Postgres with fixed schema/fixtures. Avoid hitting real AWS in automated tests.

- Test selection pitfalls
  - The test runner in this repo is MUnit; use `testOnly` with fully qualified suite names. Running a directory path (e.g., `testOnly src/test/scala`) will not work—use FQCN instead.

#### Development conventions and useful internals
- Functional style and effects
  - Prefer tagless-final and `cats` typeclasses. Constructors are expressed via givens/context bounds where practical (e.g., `given [F[_] : {Temporal, Trace, Network, Console}]: CreateSkunkSession[F] = Session.single`).
  - Encourage `Resource` + `Kleisli` patterns for DB sessions. See `CreateSkunkSession` extension methods like `inSession` and `recoverUndefinedAs` for ergonomics.

- SQL identifiers and input validation
  - See `SqlStringRegexSpec` for refined predicates: SQL identifiers follow `[A-Za-z][A-Za-z0-9_]*`, and generated passwords exclude dangerous punctuation. When adding features that touch SQL strings, validate via the existing newtypes/refinements to maintain safety.

- Tracing & logging
  - Natchez is integrated. Use `Trace[F]` in new effectful APIs where traces are relevant. For local runs, `LocalApp` wires `OpenTelemetryAtDwolla` and logs via SLF4J/Log4j2.

- Build info and runtime metadata
  - `BuildInfoPlugin` is enabled. Access via `com.dwolla.buildinfo.postgres.init.BuildInfo` (used in `LocalApp`) for name/version/etc. Keep it in sync when adding new runtime info.

- CI nuances
  - CI config is under `.dwollaci.yml` and GitHub Actions. CI uses JDK 17 and runs `sbt test`.
  - If you adjust the plugin or Java version, update CI accordingly. Use `sbt githubWorkflowGenerate` to regenerate the GitHub Actions workflow YAML.

#### Known quirks and fixes
- `build.sbt` defines `serverlessDeployCommand` as `Seq[String]`. On some Scala 3 toolchains you may need an explicit `immutable.Seq` to satisfy `settingKey[Seq[String]]`. If you see a type mismatch like `found: scala.collection.Seq[String]`, change to: `serverlessDeployCommand := scala.collection.immutable.Seq.from("serverless deploy --verbose".split(' '))` or similar.
- README JSON blocks are illustrative and may not be strict JSON; treat them as examples for CloudFormation.

#### Quickstart commands (verified)
- Full build + tests: `sbt test`
- One suite: `sbt "testOnly com.dwolla.postgres.init.ExtractRequestPropertiesSpec"`
- One test in a suite: `sbt "testOnly com.dwolla.postgres.init.SqlStringRegexSpec -- *passwords*"`
- Package universal distribution: `sbt Universal/packageBin`
- Deploy (requires env vars and Serverless): `sbt deploy`
