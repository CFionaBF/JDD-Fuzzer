# Available gadget chains per application (deduplicated)

| application | detected gadget chains (IOCDs) | known chains | verified distinct chains | verified IOCDs | deviated IOCDs | fuzzing failed | seed generation failed |
|---|---:|---:|---:|---:|---:|---:|---:|
| CC3_CommonsBeanutils_AspectJWeaver | 277 | 6 | 50 | 48 | 34 | 168 | 27 |
| cc4 | 247 | 1 | 29 | 27 | 31 | 131 | 58 |
| remo | 331 | 1 | 22 | 22 | 13 | 144 | 152 |
| rometool-Hessian | 653 | 1 | 25 | 25 | 3 | 276 | 349 |
| hibernate | 203 | 2 | 13 | 13 | 0 | 176 | 14 |
| vaadin | 719 | 1 | 40 | 43 | 23 | 344 | 309 |
| JBossInterceptors | 2 | 1 | 1 | 2 | 0 | 0 | 0 |
| javassistWeld | 2 | 1 | 1 | 2 | 0 | 0 | 0 |
| click1 | 495 | 1 | 1 | 1 | 0 | 182 | 312 |
| Fileupload | 1 | 1 | 1 | 1 | 0 | 0 | 0 |
| Wicket | 1 | 1 | 1 | 1 | 0 | 0 | 0 |
| Spring-Hessian | 1360 | 2 | 50 | 87 | 32 | 579 | 662 |
| groovy | 892 | 1 | 23 | 32 | 20 | 327 | 513 |
| BeanShell | 56 | 1 | 3 | 3 | 0 | 27 | 26 |
| JDK | 211 | 3 | 7 | 7 | 11 | 38 | 155 |
| clojure | 284 | 1 | 11 | 11 | 14 | 188 | 71 |
| Myface | 406 | 2 | 3 | 3 | 19 | 45 | 339 |
| fastjson2-Hessian | 239 | 1 | 5 | 5 | 4 | 196 | 34 |
| XBean-Hessian | 523 | 1 | 20 | 20 | 15 | 371 | 117 |
| Rhino | 496 | 2 | 2 | 2 | 26 | 74 | 394 |
| **total** | **7398** | **31** | **308** | 355 | 245 | 3266 | 3532 |

**Known gadget chains.** The known chains column gives the number of known gadget chains that JDD-Fuzzer covers in the application. We take `Method.invoke` as the key sink: once a chain reaches it, the different ways of building the sink payload that finally achieve RCE are all regarded as detecting the known chain. For known chains that trigger a dynamic proxy, the chains leading to the proxy are numerous and flexible. Therefore, a known chain is regarded as covered as long as a chain that passed the dynamic validation has the same sub-chain after the dynamic handler, the most critical part of the chain.

**Columns.** detected gadget chains = verified IOCDs + deviated IOCDs + fuzzing failed + seed generation failed.
- **verified IOCDs**: an exploitable injection object was generated from the constraints in the IOCD and it runs the IOCD's own chain to the sink.
- **deviated IOCDs**: an exploitable injection object was generated from the constraints in the IOCD, but the executed chain deviates from the IOCD's chain (it reaches the sink along another route).
- **fuzzing failed**: the IOCD was fuzzed, but no exploitable object was generated within the tries. **seed generation failed**: no initial injection object could be built, so the IOCD was not fuzzed.
- **verified distinct chains**: All unique, exploitable gadget chains. Why **verified distinct chains** > **verified IOCDs**: JDD generated exploitable injection objects from IOCDs, but some of the actual execution paths triggered by these objects deviated from the original IOCDs. JDD also recorded these additional exploitable gadget chains.


**Setup.** Oracle JDK 8u361, 2 tries x 60 s per IOCD, 4 shards in parallel; a run writes the details of each application (per-IOCD outcomes, replay verdicts, distinct chains) to `reproduction/runs/<date-time>/<application>/`, see [README.md](README.md). Fuzzing is randomized, so the results of different runs may deviate slightly.

> Since the paper we have further improved the detection engine, and the results above are overall better than those reported in the paper. Paper with the latest results: _link to be added_.
