# Third-party notices

The MIT license in this repository applies to JDrift's original code and documentation, not to third-party components.

## Gradle wrapper

`gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` are generated or supplied by Gradle 8.14.3 and retain their original license notices. Gradle is licensed under Apache License 2.0; copies of its distribution license and notice are included in `licenses/gradle-LICENSE` and `licenses/gradle-NOTICE`.

Upstream: https://github.com/gradle/gradle

## External dependencies and reference source

JMC, Kotlin, kotlinx.serialization, SQLite JDBC, JUnit, and transitive dependencies are resolved by Gradle and retain their upstream licenses. They are not relicensed by JDrift's MIT license. Packaged applications must preserve applicable dependency licenses and notices.

The local `libs/jmc` reference checkout, downloaded tools, build outputs, and generated recordings are excluded from this repository. JMC's source license remains the license supplied by OpenJDK in that checkout.
