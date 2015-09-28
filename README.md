# jfr-report-tool
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
usage: jfr-report-tool [-abdefghilmorsw] [jfrFile]
 -a,--action <action>            Tool action. Valid choices: flameGraph,
                                 stacks, topframes, dumpinfo, recordtypes
 -b,--begin <seconds>            Begin time
 -d,--duration <seconds>         Duration of time window, splits output in
                                 to multiple files
 -e,--exclude <filter>           Regexp exclude filter for methods
 -f,--first-split                First window duration half of given
                                 duration
    --flamegraph-command <cmd>   flamegraph.pl path
 -g,--grep <filter>              Regexp to include all stacks with match
                                 in any frame
 -h,--help                       Help
 -i,--include <filter>           Regexp include filter for methods
 -l,--length <seconds>           Length of selected time
 -m,--min <value>                Minimum number of samples
 -o,--output <file>              Output file
 -r,--reverse                    Process stacks in reverse order
 -s,--sort                       Sort frames
 -w,--width <pixels>             Width of flamegraph
Supported actions:
flameGraph                       creates flamegraph in svg format, default action
stacks                           creates flamegraph input file
topframes                        shows top methods
dumpinfo                         dump info
recordtypes                      dump record types
```

### Examples

This creates a file `jfr_dump_file.jfr.svg` that is the flamegraph in SVG format. SVG files can be opened with most web browsers.
```
./jfr-report-tool jfr_dump_file.jfr
```

Disabling default filtering:
```
./jfr-report-tool -e none -m 1 jfr_dump_file.jfr
```
By default, the tool removes all methods matching `^(java\.|sun\.|com\.sun\.|org\.codehaus\.groovy\.|groovy\.|org\.apache\.)` so that you can view hotspots in your own code. Use "-e none" to disable method filtering. By default, all stacks with 1 or 2 samples will be filtered. You can disable this be setting the `min` parameter to 1.





