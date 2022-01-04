#!/bin/bash
BUILD_PATH=./build
PATHS_FILE=./paths.txt

rm -rf ${BUILD_PATH}; mkdir ${BUILD_PATH}

find . -name "*.java" > ${PATHS_FILE}

javac -d ${BUILD_PATH} -cp "./lib/jackson-core-2.13.0.jar:./lib/jackson-annotations-2.13.0.jar:./lib/jackson-databind-2.13.0.jar:./lib/java-jwt-3.18.2.jar:./lib/junit-platform-console-standalone-1.8.2.jar:./lib/vavr-1.0.0-alpha-4.jar" @${PATHS_FILE}

rm ${PATHS_FILE}