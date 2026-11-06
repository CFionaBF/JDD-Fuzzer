#!/bin/sh
./build_jdd.sh 0
java -javaagent:jdd-instrumentor/target/jdd-instrumentor-1.0-SNAPSHOT.jar=0,false -jar jdd-validator/target/jdd-validator-1.0-SNAPSHOT-jar-with-dependencies.jar fuzz