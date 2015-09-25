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
usage: jfr-report-tool [-afhosw] [jfrFile]
 -a,--action <action>            Tool action
 -f,--filter <filter>            Regexp filter for methods
    --flamegraph-command <cmd>   flamegraph.pl path
 -h,--help                       Help
 -o,--output <file>              Output file
 -s,--sort                       Sort frames
 -w,--width <pixels>             Width of flamegraph
```

Example:
```
./jfr-report-tool jfr_dump_file.jfr
```
This creates a file `jfr_dump_file.jfr.svg` that is the flamegraph in SVG format. SVG files can be opened with most web browsers.