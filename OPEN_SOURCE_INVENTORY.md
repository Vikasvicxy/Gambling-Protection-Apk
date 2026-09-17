# Shield — Open-Source Usage Inventory

Direct and notable transitive third-party components used by Shield, with license families.
Versions from `gradle/libs.versions.toml` (date: 2026-09-17). Full license texts are in
`THIRD_PARTY_NOTICES.md`.

## Android / Jetpack (Apache-2.0)
| Component | Version | Used for |
|---|---|---|
| androidx.core:core-ktx | 1.19.0 | Kotlin Android APIs |
| androidx.core:core-splashscreen | 1.2.0 | splash screen |
| androidx.activity:activity-compose | 1.13.0 | Compose activity |
| androidx.navigation:navigation-compose | 2.10.1 | navigation |
| androidx.compose (BOM) | 2026.09.00 | UI (ui, material3, material-icons-extended, foundation, animation, tooling, test) |
| androidx.lifecycle:* | 2.11.0 | ViewModel / lifecycle / process |
| androidx.datastore:datastore-preferences | 1.2.1 | preferences storage |
| androidx.room:* | 2.8.5 | local persistence (Room + KSP) |
| androidx.work:work-runtime-ktx | 2.11.2 | background checks/updates |
| androidx.test / androidx.arch.core:core-testing | 1.x / 2.2.0 | test infrastructure |

## Kotlin / tooling (Apache-2.0 unless noted)
| Component | Version | Used for |
|---|---|---|
| org.jetbrains.kotlin | 2.3.21 | language + compose/serialization plugins |
| org.jetbrains.kotlinx:kotlinx-coroutines | 1.11.0 | coroutines |
| org.jetbrains.kotlinx:kotlinx-serialization-json | 1.11.0 | JSON DTO codec (device + server) |
| com.google.devtools.ksp | 2.3.12 | apt for Room/Hilt |

## DI & HTTP
| Component | Version | License | Used for |
|---|---|---|---|
| com.google.dagger:hilt-android & hilt-android-compiler | 2.60.1 | Apache-2.0 | dependency injection |
| androidx.hilt:hilt-navigation-compose / hilt-work / hilt-compiler | 1.3.0 | Apache-2.0 | Hilt Compose binding |
| com.squareup.okhttp3:okhttp | 5.5.0 | Apache-2.0 | backend client for relay endpoints |

## Testing (test-only)
| Component | Version | License |
|---|---|---|
| junit:junit | 4.13.2 | EPL-2.0 |
| com.google.truth:truth | 1.4.5 | Apache-2.0 |
| org.robolectric:robolectric | 4.17 | Apache-2.0 |
| app.cash.turbine:turbine | 1.2.1 | Apache-2.0 |

## Build
| Component | Version | License |
|---|---|---|
| com.android.tools.build:gradle (AGP) | 9.4.0 | Apache-2.0 |
| Gradle (wrapper) | 9.7.1 | Apache-2.0 |

## Serverless reference implementation (serverless/ — not deployed)
| Component | Version | License |
|---|---|---|
| @cloudflare/workers-types | 4.x | BSD-3-Clause (Cloudflare) |
| typescript | 5.7+ | Apache-2.0 |
| wrangler | 3.x | Apache-2.0 |

No code is copied verbatim from any third-party source into Shield's own source; all
dependencies are used as libraries/build tools under their respective licenses.