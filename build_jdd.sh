#!/bin/bash

if [ $# -ne 1 ]; then
    echo "Usage: $0 <is_local>"
    exit 1
fi

is_debug=$1

if [ $is_debug -eq 1 ]; then
    echo "[+] local now!"
else
    echo "[+] not local now!"
    # package all jdd-fuzzer jars
    java_home=$PWD/jdk-for-jdd/jdk1.8.0_361
    if [ -d "$java_home" ]; then
        echo "[+] JDK for JDD exists!"
    else
        echo "[-] JDK for JDD does not exist, please make sure it under ./jdk-for-jdd/jdk1.8.0_361"
        exit 1
    fi

    export JAVA_HOME=$java_home
    export PATH=$JAVA_HOME/bin:$PATH
    if [ -f ~/.bash_profile_mvn ]; then
        echo "[+] mvn enviroment variable config file exists!"
    else
        echo "[-] mvn enviroment variable config file does not exist, please make sure it under ~/.bash_profile_mvn"
        exit 1
    fi
    . ~/.bash_profile_mvn # a custom mvn enviroment variable config file
fi

echo "[+] Building jdd-fuzzer...."
    mvn clean install -Dmaven.test.skip=true # > /dev/null 2>&1
    if [ $? -eq 0 ]; then
        echo "[+] jdd-fuzzer build success!"
    else
        echo "[-] jdd-fuzzer build failed!"
        exit 1
    fi

# collect jdd-fuzzer libraries
folder_path="./jdd-libs"
if [ -d "$folder_path" ]; then
    echo "[+] jdd-libs Folder exists, collect all libs..."
else
    echo "[+] jdd-libs Folder does not exist, make it!"
    mkdir $folder_path
    if [ $? -eq 0 ]; then
        echo "[+] jdd-libs Folder created, start to collect all libs..."
    else
        echo "[-] jdd-libs Folder creation failed!"
        exit 1
    fi
fi
cp jdd-validator/target/jdd-validator-1.0-SNAPSHOT-jar-with-dependencies.jar $folder_path
cp jdd-validator/target/jdd-validator-1.0-SNAPSHOT.jar $folder_path
cp jdd-data/target/jdd-data-1.0-SNAPSHOT.jar $folder_path
cp jdd-exceptions/target/jdd-exceptions-1.0-SNAPSHOT.jar $folder_path
cp jdd-utils/target/jdd-utils-1.0-SNAPSHOT.jar $folder_path
cp jdd-instrumentor/target/jdd-instrumentor-1.0-SNAPSHOT.jar $folder_path
# cp jdd-instrumentor/target/jdd-instrumentor-1.0-SNAPSHOT-jar-with-dependencies.jar $folder_path

echo "[+] jdd-fuzzer is ready!"
echo "run \`java -jar jdd-validator/target/jdd-validator-1.0-SNAPSHOT-jar-with-dependencies.jar\` to start!"

