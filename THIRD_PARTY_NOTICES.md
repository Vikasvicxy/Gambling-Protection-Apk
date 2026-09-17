# Shield — Third-Party Notices

This product includes software developed by third parties. Maintainers must keep version
references (`gradle/libs.versions.toml`, `serverless/worker/package.json`) in sync with the
licenses below. See `OPEN_SOURCE_INVENTORY.md` for the full component list.

## Apache License 2.0
Applies to (among others): AndroidX / Jetpack, Kotlin, kotlinx-coroutines,
kotlinx-serialization, KSP, Hilt, OkHttp, androidx.hilt, Room helpers, DataStore, WorkManager,
Lifecycle, Navigation, Compose, Turbine, Truth, Robolectric, AGP, Gradle, TypeScript, wrangler.

> Licensed under the Apache License, Version 2.0 (the "License"); you may not use these files
> except in compliance with the License. You may obtain a copy of the License at
> http://www.apache.org/licenses/LICENSE-2.0
>
> Unless required by applicable law or agreed to in writing, software distributed under the
> License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
> either express or implied. See the License for the specific language governing permissions
> and limitations under the License.

## Eclipse Public License 2.0
junit:junit (4.13.2) — used under its EPL-2.0 terms for testing only.

> https://www.eclipse.org/legal/epl-2.0/

## BSD 3-Clause (Cloudflare)
@cloudflare/workers-types — used as TypeScript type definitions for the reference backend.

> Redistribution and use in source and binary forms, with or without modification, are
> permitted provided that the conditions of the BSD 3-Clause license are met.
> https://github.com/cloudflare/workers-types

---

This notice list covers direct dependencies at compile/test time. Each transitive component is
distributed under the license declared in its own artifact metadata; for exact notices of any
artifact, see `THIRD_PARTY_NOTICES` bundled with the artifact or its upstream repository.