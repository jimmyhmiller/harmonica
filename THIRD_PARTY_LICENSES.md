# Third-Party Licenses

This document lists all third-party code referenced or included in this repository
and their respective licenses. Harmonica itself is licensed under AGPL-3.0 (see
`LICENSE`); the items below are attributed under their own terms and remain
governed by those terms.

## Quick Reference

| Component / Directory | Source | License |
|-----------------------|--------|---------|
| Bytecode IR design (opcode set, operand model, dump format, register-pool allocator, intrinsics layout) | Ladybird LibJS | BSD-2-Clause |
| `test-oracles/test262/` | ECMAScript Test Suite | BSD-3-Clause |
| `test-oracles/test262-cache/` | Derived ASTs from test262 | BSD-3-Clause |
| `test-oracles/curated/` | Various open-source projects | MIT / Apache-2.0 |
| `benchmarks/real-world-libs/` | npm packages (downloaded) | MIT / Apache-2.0 |

For detailed license information about curated test fixtures, see `test-oracles/curated/README.md`.

---

## Ladybird LibJS (design attribution)

**Source:** https://github.com/LadybirdBrowser/ladybird (`Libraries/LibJS/`),
formerly part of SerenityOS (https://github.com/SerenityOS/serenity).

**License:** BSD-2-Clause (`SPDX-License-Identifier: BSD-2-Clause`)

**How it is used here:** No LibJS source code is copied or vendored into this
repository. Harmonica's bytecode IR, opcode names, operand model, disassembler
dump format, register-pool allocator strategy, and the structural layout of
runtime intrinsics / standard objects are *adapted from* LibJS so that LibJS
can serve as a byte-perfect behavioral oracle. Concretely, files under
`harmonica-core/src/main/java/com/jimmyhmiller/harmonica/bytecode/` (notably
`Op.java`, `Operand.java`, `AbstractOps.java`, `Generator.java`, `Executable.java`,
`Disassembler.java`) and the design notes in `docs/standard-objects.md` and
`docs/bytecode.md` are informed by LibJS. All implementations are written from
scratch in Java; comments cite LibJS only as the design reference.

The BSD-2-Clause license text reproduced below applies to LibJS itself. Where
this project's design follows LibJS, the LibJS copyright notices are preserved
by reference under this attribution.

```
Copyright (c) Andreas Kling <kling@serenityos.org> and Ladybird / SerenityOS
LibJS contributors.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

For canonical, per-file copyright headers, see the upstream sources at
`Libraries/LibJS/` in the Ladybird repository.

## Test262 Test Suite

**Location:** `test-oracles/test262/` (submodule), `test-oracles/test262-cache/` (derived AST cache)

**Source:** https://github.com/tc39/test262

**License:** BSD 3-Clause

```
Test262: ECMAScript Test Suite ("Software") is protected by copyright and is being
made available under the "BSD License", included below.

Copyright (C) 2012 Ecma International
All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.
3. Neither the name of the authors nor Ecma International may be used to endorse
   or promote products derived from this software without specific prior written
   permission.

THIS SOFTWARE IS PROVIDED BY THE ECMA INTERNATIONAL "AS IS" AND ANY EXPRESS OR
IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
SHALL ECMA INTERNATIONAL BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT
OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

---

## Real-World Benchmark Libraries

The following libraries are downloaded for benchmarking purposes via `benchmarks/download-real-world-libs.sh`.

### TypeScript

**Location:** `benchmarks/real-world-libs/typescript.js`, `benchmarks/real-world-libs-es5/typescript.es5.js`

**Version:** 5.3.3

**Source:** https://www.typescriptlang.org/

**License:** Apache-2.0

```
Copyright (c) Microsoft Corporation. All rights reserved.

Licensed under the Apache License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License. You may obtain a copy of the
License at http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed
under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied. See the License for the
specific language governing permissions and limitations under the License.
```

### React

**Location:** `benchmarks/real-world-libs/react.production.min.js`, `benchmarks/real-world-libs-es5/react.production.min.es5.js`

**Version:** 18.2.0

**Source:** https://react.dev/

**License:** MIT

```
MIT License

Copyright (c) Meta Platforms, Inc. and affiliates.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### React DOM

**Location:** `benchmarks/real-world-libs/react-dom.production.min.js`, `benchmarks/real-world-libs-es5/react-dom.production.min.es5.js`

**Version:** 18.2.0

**Source:** https://react.dev/

**License:** MIT (same as React above)

### Vue.js

**Location:** `benchmarks/real-world-libs/vue.global.prod.js`, `benchmarks/real-world-libs-es5/vue.global.prod.es5.js`

**Version:** 3.3.13

**Source:** https://vuejs.org/

**License:** MIT

```
MIT License

Copyright (c) 2018-present, Yuxi (Evan) You

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### Lodash

**Location:** `benchmarks/real-world-libs/lodash.js`, `benchmarks/real-world-libs-es5/lodash.es5.js`

**Version:** 4.17.21

**Source:** https://lodash.com/

**License:** MIT

```
MIT License

Copyright OpenJS Foundation and other contributors <https://openjsf.org/>

Based on Underscore.js, copyright Jeremy Ashkenas,
DocumentCloud and Investigative Reporters & Editors <http://underscorejs.org/>

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

### Three.js

**Location:** `benchmarks/real-world-libs/three.js`, `benchmarks/real-world-libs-es5/three.es5.js`

**Version:** 0.159.0

**Source:** https://threejs.org/

**License:** MIT

```
MIT License

Copyright (c) 2010-2024 three.js authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
