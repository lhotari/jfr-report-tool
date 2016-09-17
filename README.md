# jfr-report-tool
[![Build Status](https://travis-ci.org/lhotari/jfr-report-tool.svg?branch=master)](https://travis-ci.org/lhotari/jfr-report-tool)

Tool for creating reports from Java Flight Recorder dumps.

Influenced by https://github.com/chrishantha/jfr-flame-graph . Kudos to @chrishantha for the great work.

Uses [Java Flight Recorder internal API to read JFR dump files](http://hirt.se/blog/?p=446).

Uses [flamegraph.pl](https://raw.githubusercontent.com/brendangregg/FlameGraph/master/flamegraph.pl) from  [FlameGraph](https://github.com/brendangregg/FlameGraph) for creating flamegraphs in SVG format.

## Requirements

`JAVA_HOME` environment variable must point to Oracle JDK 1.7.0_40+.

## Usage with provided shell script (requires bash)

```
./jfr-report-tool [dump_file.jfr]
```
The script will build the tool on first access using `./gradlew shadowJar` command. It will also use `curl` to download [flamegraph.pl](https://raw.githubusercontent.com/brendangregg/FlameGraph/master/flamegraph.pl) if it's not available on `PATH`.

```
usage: jfr-report-tool [-abcdefghilmnorsw] [jfrFile]
 -a,--action <action>                   Tool action. Valid choices:
                                        flameGraph, stacks, topframes,
                                        dumpinfo, recordtypes
    --allocations                       Allocation flamegraph
 -b,--begin <seconds>                   Begin time
 -c,--cutoff <pattern>                  Cut off frame pattern
 -d,--duration <seconds>                Duration of time window, splits
                                        output in to multiple files
 -e,--exclude <filter>                  Regexp exclude filter for methods
 -f,--first-split                       First window duration half of
                                        given duration
    --flamegraph-command <cmd>          flamegraph.pl path
 -g,--grep <filter>                     Regexp to include all stacks with
                                        match in any frame
 -h,--help                              Help
 -i,--include <filter>                  Regexp include filter for methods
 -l,--length <seconds>                  Length of selected time
 -m,--min <value>                       Minimum number of samples
    --min-samples-frame-depth <value>   Minimum samples sum taken at frame
                                        depth
 -n,--no-compress                       Don't compress package names
 -o,--output <file>                     Output file
 -r,--reverse                           Process stacks in reverse order
 -s,--sort                              Sort frames
 -w,--width <pixels>                    Width of flamegraph
Supported actions:
flameGraph                       creates flamegraph in svg format, default action
stacks                           creates flamegraph input file
topframes                        shows top methods
dumpinfo                         dump info
recordtypes                      dump record types
```

### Examples

#### Flamegraph

This creates a file `jfr_dump_file.jfr.svg` that is the flamegraph in SVG format. SVG files can be opened with most web browsers.
```
./jfr-report-tool jfr_dump_file.jfr
```

#### Disabling default filtering

```
./jfr-report-tool -e none -m 1 jfr_dump_file.jfr
```
By default, the tool removes all methods matching `^(java\.|sun\.|com\.sun\.|org\.codehaus\.groovy\.|groovy\.|org\.apache\.)` so that you can view hotspots in your own code. Use "-e none" to disable method filtering. By default, all stacks with 1 or 2 samples will be filtered. You can disable this be setting the `min` parameter to 1.

#### Allocations flamegraph

To visualize allocations, there is a new feature to render a flamegraph where each stacktrace is weighted with the allocation size made in each method. It uses the JFR `java/object_alloc_in_new_TLAB` and `java/object_alloc_outside_TLAB` events to get allocation data. These events are enabled when using `settings=profile` in `FlightRecorderOptions`.

```
./jfr-report-tool --allocations jfr_dump_file.jfr
```

## Java Flight Recorder

### Enabling Java Flight Recorder

Add these JVM startup parameters
```
-XX:+UnlockCommercialFeatures -XX:+FlightRecorder -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -XX:FlightRecorderOptions=stackdepth=1024
```

Use this `FlightRecorderOptions` parameter to start recording from the start and create a dump on exit to the current directory:
```
-XX:FlightRecorderOptions=defaultrecording=true,settings=profile,disk=true,maxsize=500M,stackdepth=1024,dumponexit=true
```
This uses $JAVA_HOME/jre/lib/jfr/profile.jfc settings which has method sampling enabled.


### Controlling Java Flight Recorder at runtime from command line

`jcmd` is used to control JFR.

Help for all `jcmd` commands:
```
jcmd <PID> help
```
You can use `jps` to find the process id (PID) of the java process you want to profile.

The available commands are `JFR.stop`, `JFR.start`, `JFR.dump`, `JFR.check`

Help for `JFR.start`
```
jcmd <PID> help JFR.start
```

### Creating a recording by jcmd

starting recording with setting from $JAVA_HOME/jre/lib/jfr/profile.jfc
```
jcmd <PID> JFR.start name=myrecording settings=profile
```

dumping to file and continuing recording
```
jcmd <PID> JFR.dump name=myrecording filename=$PWD/mydump.jfr
```

### Customizing profiling settings

It's recommended to create a custom JFR settings file with highest sampling rate (10ms). [profiling.jfc](https://github.com/lhotari/gradle-profiling/blob/master/jfr/profiling.jfc) example.
You can use the Java Mission Control UI to edit JFR setting files. The feature is called "Java Flight Recording Template Manager".

### Controlling Java Flight Recorder at runtime from graphical user interface

Use `jmc` command to start the Java Mission Control UI. The UI can be used to do recordings.










