# BE-P7-07 (build-engineer): `sbt packageJobs` — orchestrator-condensed record

- STATUS: COMPLETE. One foreground `sbt packageJobs` (about 3 s); no file changes; no marker write; four `[success]` steps (10:54:11-10:54:13 PM, Nov 20, 2026); sbt 1.11.7, Java 17.0.17.
- sparkJobAssemblyCheck PASS: scholarchain-spark-jobs-0.1.0.jar, Main-Class scholarchain.spark.job.CollaborationJob; no class under the 13 forbidden prefixes incl. io/netty/, org/xerial/snappy/, com/github/luben/, io/airlift/compress/, net/jpountz/; 562 scala/ classes all from scala3-library_3 3.3.6; 2916 entries; bytes=7621816 sha256=cd62042c642b11a62be290128cc97b28ed03b9b55d1808faf8b5fed1dbaf597a.
- hadoopJobAssemblyCheck PASS: scholarchain-hadoop-jobs-0.1.0.jar, 6432 entries, none under the 13 forbidden prefixes; bytes=15333453 sha256=cff30c7d97b79da3390e717ae040bde306c865b14e0324b6155bd366b89acd04.
- Risks: both jars reported "Assembly jar up to date" (freshness relies on the sbt-assembly cache); the hadoop check covers forbidden prefixes only (no Main-Class/Scala-version/timestamp checks); sha256 values describe uncommitted working-tree state.
