package io.github.lhotari

import com.google.common.util.concurrent.AtomicLongMap
import com.jrockit.mc.common.IMCFrame
import com.jrockit.mc.flightrecorder.FlightRecording
import com.jrockit.mc.flightrecorder.FlightRecordingLoader
import com.jrockit.mc.flightrecorder.internal.model.FLRStackTrace
import com.jrockit.mc.flightrecorder.internal.parser.binary.InvalidFlrFileException
import com.jrockit.mc.flightrecorder.spi.*
import com.jrockit.mc.flightrecorder.util.TimeRange
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType

import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

@CompileStatic
class JfrReportTool {
    private static
    final Map<String, String> DEFAULT_EXTENSION = [flameGraph: 'svg', stacks: 'txt', topframes: 'top.txt']
    private static final String SAMPLING_EVENT_PATH = "vm/prof/execution_sample"
    private static final String JVM_INFO_EVENT_PATH = "vm/info"
    private static final String OS_INFO_EVENT_PATH = "os/information"
    private static final String CPU_INFO_EVENT_PATH = "os/processor/cpu_information"
    private static final String MEM_INFO_EVENT_PATH = "os/memory/physical_memory"
    private static final String RECORDING_LOST_EVENT_PATH = "recordings/buffer_lost"
    private static final String ALLOCATION_IN_TLAB_EVENT_PATH = "java/object_alloc_in_new_TLAB"
    private static final String ALLOCATION_OUTSIDE_TLAB_EVENT_PATH = "java/object_alloc_outside_TLAB"
    private static final String EXCEPTION_THROW_EVENT_PATH = "java/exception_throw"
    private static final String ERROR_THROW_EVENT_PATH = "java/error_throw"
    private static
    final Set<String> INFO_EVENT_PATHS = [JVM_INFO_EVENT_PATH, OS_INFO_EVENT_PATH, CPU_INFO_EVENT_PATH, MEM_INFO_EVENT_PATH, RECORDING_LOST_EVENT_PATH] as Set
    private Set<String> filteredEventPaths = ([SAMPLING_EVENT_PATH] as Set) + INFO_EVENT_PATHS
    Pattern excludeFilter = ~/^(java\.|sun\.|com\.sun\.|org\.codehaus\.groovy\.|groovy\.|org\.apache\.)/
    Pattern includeFilter = null
    Pattern grepFilter = null
    Pattern cutOffFilter = null
    int flameGraphWidth = 1850
    String flameGraphCommand = "flamegraph.pl"
    boolean compressPackageNames = true
    boolean sortFrames = false
    int minimumSamples = 3
    int minimumSamplesFrameDepth = 5
    int timeWindowDuration = -1
    Closure<?> outputMessage = {}
    boolean reverse = false
    int begin
    int length
    boolean firstSplit
    int stackTracesTruncated
    Map<String, IEvent> infoEvents = [:]
    int recordingBuffersLost = 0
    FlameGraphType flameGraphType = FlameGraphType.DEFAULT
    AllocationMethod allocationMethod

    enum FlameGraphType {
        DEFAULT,
        ALLOCATIONS,
        EXCEPTIONS
    }

    @ReportAction("creates flamegraph in svg format, default action")
    def flameGraph(File jfrFile, File outputFile) {
        handleRecordingByWindowByFile(jfrFile, outputFile) { IView view, File currentOutputFile ->
            File tempFile = File.createTempFile("flamegraph_input", ".txt")
            def entryCount = tempFile.withWriter { writer ->
                convertToFlameGraphFormat(view, writer)
            }
            if (entryCount) {
                if (stackTracesTruncated) {
                    println "WARNING: Some stacktraces ($stackTracesTruncated) were truncated. Use stacktrace=1024 JFR option in recording to fix this."
                }
                ProcessBuilder builder = new ProcessBuilder(flameGraphCommand, "--width", flameGraphWidth.toString())
                builder.command().with {
                    add('--title')
                    add(buildTitle(view))
                    add(tempFile.absolutePath)
                }
                builder.redirectOutput(currentOutputFile)
                Process process = builder.start()
                process.waitFor()
            }
            tempFile.delete()
        }
        if (infoEvents) {
            File descriptionFile = new File(outputFile.getParentFile(), outputFile.getName() + ".info.txt")
            descriptionFile.withPrintWriter { PrintWriter writer ->
                for (String eventTypePath : INFO_EVENT_PATHS) {
                    IEvent event = infoEvents.get(eventTypePath)
                    if (event != null) {
                        printEventFields(event, writer)
                    }
                }
                if (recordingBuffersLost) {
                    writer.println("${recordingBuffersLost} recording buffers lost.")
                }
            }
        }
    }

    private String buildTitle(IView view) {
        def dateFormatter = {
            new Date(((it as long) / 1000000L).longValue()).format("yyyy-MM-dd HH:mm:ss")
        }
        def titleBuilder = new StringBuilder()
        if (flameGraphType != FlameGraphType.DEFAULT) {
            titleBuilder.append(flameGraphType.toString().capitalize()).append(" ")
        }
        titleBuilder.append("Started ${dateFormatter(view.range.startTimestamp)}")
        IEvent jvmInfoEvent = infoEvents.get(JVM_INFO_EVENT_PATH)
        if (jvmInfoEvent != null) {
            titleBuilder.append(" ")
            def appArgs = jvmInfoEvent.getValue("javaArguments")
            if (appArgs) {
                titleBuilder.append("App args:")
                titleBuilder.append(appArgs)
                titleBuilder.append(" ")
            }
        }
        titleBuilder.toString()
    }

    @ReportAction("creates flamegraph input file")
    def stacks(File jfrFile, File outputFile) {
        handleRecordingByWindowByFile(jfrFile, outputFile) { IView view, File currentOutputFile ->
            currentOutputFile.withWriter { writer ->
                convertToFlameGraphFormat(view, writer)
            }
        }
    }

    @ReportAction("shows top methods")
    def topframes(File jfrFile, File outputFile) {
        handleRecordingByWindowByFile(jfrFile, outputFile) { IView view, File currentOutputFile ->
            AtomicLongMap<String> methodCounts = AtomicLongMap.create()
            forEachFLRStackTrace(view) { FLRStackTrace flrStackTrace, IEvent event ->
                List<String> stackTrace = convertStackTrace(flrStackTrace)
                if (matchesGrepFilter(stackTrace)) {
                    stackTrace.each { String methodSignature ->
                        if (matchesMethod(methodSignature)) {
                            methodCounts.incrementAndGet(methodSignature)
                        }
                    }
                }
            }
            currentOutputFile.withWriter { writer ->
                writeStackCounts(methodCounts.asMap(), writer, true)
            }
        }
    }

    @ReportAction("dump info")
    def dumpinfo(File jfrFile) {
        def recording = loadJfrFile(jfrFile)
        def view = recording.createView()
        view.setFilter(new IEventFilter() {
            boolean accept(IEvent iEvent) {
                iEvent.eventType.path in INFO_EVENT_PATHS
            }
        })
        Set<String> seen = [] as Set
        PrintWriter pw = new PrintWriter(System.out)
        for (IEvent event : view) {
            String eventTypeName = event.eventType.name
            if (!seen.contains(eventTypeName)) {
                printEventFields(event, pw, true)
                seen.add(eventTypeName)
            }
        }
    }

    private FlightRecording loadJfrFile(File jfrFile) {
        if (jfrFile.exists()) {
            try {
                return FlightRecordingLoader.loadFile(jfrFile)
            } catch (RuntimeException e) {
                if(e.cause instanceof InvalidFlrFileException) {
                    try {
                        // attempt gunzipping since loadFile method doesn't handle compressed files
                        println "Attempting uncompressing input with gzip on the fly..."
                        return FlightRecording.cast(jfrFile.withInputStream { input ->
                            new GZIPInputStream(input).withStream { gunzipInput ->
                                try {
                                    FlightRecordingLoader.getMetaClass().invokeStaticMethod(FlightRecordingLoader, 'loadStream', [gunzipInput] as Object[])
                                } catch (MissingMethodException mme) {
                                    println "The current version of Java doesn't support loading a JFR from stream input. Please upgrade your JVM version or manually gunzip the JFR file before using this tool."
                                    throw e
                                }
                            }
                        })
                    } catch (ZipException zipException) {
                        if (zipException.message == 'Not in GZIP format') {
                            println "Input wasn't in GZIP format."
                            // throw original exception since input wasn't in GZIP format
                            throw e
                        }
                        throw zipException
                    }
                }
                throw e
            }
        } else {
            throw new FileNotFoundException(jfrFile.absolutePath)
        }
    }

    private void printEventFields(IEvent event, PrintWriter out, boolean showTypeInfo = false) {
        def eventType = event.eventType
        out.println "${eventType.name.padRight(33)}${showTypeInfo ? eventType.path.padRight(33) : ''}$eventType.description"
        eventType.getFields().each { IField field ->
            def fieldValue = field.getValue(event)
            out.println "${field.name.padRight(33)}${showTypeInfo ? field.identifier.padRight(33) : ''}${showTypeInfo ? fieldValue?.getClass()?.getSimpleName()?.padRight(20) : ''}${fieldValue}"
        }
        out.println()
    }

    @CompileDynamic
    @ReportAction("dump record types")
    def recordtypes(File jfrFile) {
        def recording = loadJfrFile(jfrFile)
        recording.m_repository.eventTypes.each { IEventType eventType ->
            println "${eventType.name.padRight(33)}${eventType.path.padRight(33)}$eventType.description"
        }
    }

    void handleRecordingByWindowByFile(File jfrFile, File outputFile, Set<String> acceptedEventTypes = filteredEventPaths, Closure<?> handler) {
        handleRecordingByWindow(jfrFile, acceptedEventTypes) { IView view, int fileNumber ->
            File currentOutputFile
            if (fileNumber > 1) {
                currentOutputFile = createNewFileName(fileNumber, outputFile)
            } else {
                currentOutputFile = outputFile
            }
            handler(view, currentOutputFile)
            if (currentOutputFile.length() > 0) {
                outputMessage.call(currentOutputFile)
            } else {
                currentOutputFile.delete()
            }
        }
    }

    private File createNewFileName(int fileNumber, File templateFile) {
        String fileName = templateFile.getName()
        List<String> m = (List<String>) (fileName =~ ~/^(.*\.)(.*?)$/).find { it }
        if (m) {
            fileName = "${m[1]}${fileNumber}.${m[2]}"
        } else {
            fileName = "${fileName}.${fileNumber}"
        }
        new File(templateFile.getParentFile() ?: new File(''), fileName)
    }

    void handleRecordingByWindow(File jfrFile, Set<String> acceptedEventTypes, Closure<?> handler) {
        def recording = loadJfrFile(jfrFile)
        def fullRange = recording.timeRange
        long startTime = fullRange.startTimestamp + TimeUnit.SECONDS.toNanos(begin)
        long fullRangeEnd
        if (length > 0) {
            fullRangeEnd = startTime + TimeUnit.SECONDS.toNanos(length)
        } else {
            fullRangeEnd = fullRange.endTimestamp
        }
        long windowDuration = timeWindowDuration > 0 ? TimeUnit.SECONDS.toNanos(timeWindowDuration) : (fullRangeEnd - startTime)
        int fileNumber
        while (startTime < fullRangeEnd) {
            fileNumber++
            long endTime = startTime + ((firstSplit && fileNumber == 1 ? windowDuration / 2 : windowDuration) as long)
            IView view = createView(recording, acceptedEventTypes)
            view.setRange(new TimeRange(startTime, endTime))
            handler(view, fileNumber)
            startTime = endTime + 1
        }
    }

    boolean matchesGrepFilter(List<String> stackTrace) {
        grepFilter == null || stackTrace.any { String methodSignature -> methodSignature && methodSignature =~ grepFilter }
    }

    private boolean matchesMethod(String methodSignature) {
        (includeFilter == null || methodSignature =~ includeFilter) && (excludeFilter == null || !(methodSignature =~ excludeFilter))
    }

    void forEachFLRStackTrace(IView view,
                              @ClosureParams(value = SimpleType, options = ["com.jrockit.mc.flightrecorder.internal.model.FLRStackTrace", "com.jrockit.mc.flightrecorder.spi.IEvent"]) Closure<?> handler) {
        for (IEvent event : view) {
            if (!handleInfoEvents(event)) {
                FLRStackTrace flrStackTrace = (FLRStackTrace) event.getValue("(stackTrace)")
                if (flrStackTrace != null) {
                    if (flrStackTrace.truncationState?.isTruncated()) {
                        stackTracesTruncated++
                    }
                    handler(flrStackTrace, event)
                }
            }
        }
    }

    private boolean handleInfoEvents(IEvent event) {
        def eventTypePath = event.eventType.path
        if (eventTypePath in INFO_EVENT_PATHS) {
            if (eventTypePath == RECORDING_LOST_EVENT_PATH) {
                recordingBuffersLost++
            } else {
                infoEvents.put(eventTypePath, event)
            }
            return true
        }
        return false
    }

    private IView createView(FlightRecording recording, Set<String> acceptedEventTypes) {
        IView view = recording.createView()
        if (acceptedEventTypes) {
            view.setFilter(new IEventFilter() {
                boolean accept(IEvent iEvent) {
                    iEvent.eventType.path in acceptedEventTypes
                }
            })
        }
        view
    }

    int convertToFlameGraphFormat(IView view, Writer writer) {
        AtomicLongMap<String> stackCounts = AtomicLongMap.create()
        StackTraceRoots root = new StackTraceRoots()
        forEachFLRStackTrace(view) { FLRStackTrace flrStackTrace, IEvent event ->
            def stackTrace = convertStackTrace(flrStackTrace)
            long weight = 1
            if (flameGraphType == FlameGraphType.ALLOCATIONS && allocationMethod == AllocationMethod.SIZE) {
                weight = (Long) event.getValue("allocationSize")
            }

            if (matchesGrepFilter(stackTrace)) {
                def filtered = stackTrace
                if (cutOffFilter != null) {
                    int cutOffMatchIndex = filtered.findIndexOf { String entry ->
                        entry =~ cutOffFilter
                    }
                    if (cutOffMatchIndex > -1) {
                        filtered = filtered.subList(0, cutOffMatchIndex)
                    }
                }
                filtered = filtered.findAll { matchesMethod(it) }

                if (filtered) {
                    if (!reverse) {
                        filtered = filtered.reverse()
                    }
                    if (minimumSamples > 1) {
                        root.addStackTrace(filtered, minimumSamplesFrameDepth, weight)
                    } else {
                        stackCounts.addAndGet(formatStackFrames(filtered), weight)
                    }
                }
            }
        }

        if (minimumSamples > 1) {
            for (StackTraceRoot listOfStacks : root.roots.values()) {
                if (listOfStacks.stacks.size() > minimumSamples) {
                    for (StackTraceEntry stackTraceFrames : listOfStacks.stacks) {
                        stackCounts.addAndGet(formatStackFrames(stackTraceFrames.frames), stackTraceFrames.weight)
                    }
                }
            }
        }

        writeStackCounts(stackCounts.asMap(), writer, sortFrames)
    }

    private String formatStackFrames(List<String> frames) {
        frames.collect { formatMethodName(it) }.join(';')
    }

    @CompileStatic
    static class StackTraceRoots {
        Map<String, StackTraceRoot> roots = new HashMap<String, StackTraceRoot>()

        void addStackTrace(List<String> frames, int minimumSamplesFrameDepth, long weight) {
            String rootKey = frames.take(minimumSamplesFrameDepth).join(';')
            StackTraceRoot listOfStacks = roots.get(rootKey)
            if (listOfStacks == null) {
                listOfStacks = new StackTraceRoot()
                roots.put(rootKey, listOfStacks)
            }
            listOfStacks.addStack(frames, weight)
        }
    }

    @CompileStatic
    static class StackTraceRoot {
        List<StackTraceEntry> stacks = []

        void addStack(List<String> frames, long weight) {
            stacks.add(new StackTraceEntry(frames, weight))
        }
    }

    @CompileStatic
    @TupleConstructor
    static class StackTraceEntry {
        List<String> frames
        long weight
    }

    private List<String> convertStackTrace(FLRStackTrace flrStackTrace) {
        flrStackTrace.frames.collect { frame ->
            convertToMethodSignature((IMCFrame) frame)
        }?.findAll { it }
    }

    String convertToMethodSignature(IMCFrame frame) {
        // getHumanReadable(boolean showReturnValue, boolean useQualifiedReturnValue, boolean showClassName, boolean useQualifiedClassName, boolean showArguments, boolean useQualifiedArguments)
        frame.method?.getHumanReadable(false, true, true, true, true, true)
    }

    private int writeStackCounts(Map<String, Long> map, Writer writer, boolean sort) {
        def counter = 0
        def writeEntry = { Map.Entry<String, Long> entry ->
            writer.write entry.key
            writer.write ' '
            writer.write entry.value.toString()
            writer.write '\n'
            counter++
        }
        sort ? map.collect { entry -> entry }.sort { a, b -> b.value <=> a.value }.each(writeEntry) : map.each(writeEntry)
        counter
    }

    String formatMethodName(String s) {
        if (compressPackageNames) {
            List<String> m = (List<String>) (s =~ /^(.*?)\((.*)\)$/).find { it }
            if (m) {
                return { String classAndMethod, String arguments ->
                    def compactMethod = classAndMethod.split(/\./).takeRight(3).join('.')
                    def compactArgs = arguments.split(/, /).collect { String argType ->
                        argType.split(/\./).last()
                    }.join(', ')
                    "$compactMethod(${compactArgs ?: ''})".toString()
                }(m[1], m[2])
            }
        }
        s
    }

    static Map<String, String> scanReportActions(Class clazz) {
        Map<String, String> reportActions = [:]
        clazz.getDeclaredMethods().each { Method method ->
            ReportAction reportAction = method.getAnnotation(ReportAction)
            if (reportAction) {
                reportActions.put(method.name, reportAction.value())
            }
        }
        reportActions
    }

    @CompileDynamic
    public static void main(String[] args) {
        def cli = new CliBuilder()
        cli.stopAtNonOption = false
        def reportActions = scanReportActions(JfrReportTool)
        cli.with {
            h 'Help', longOpt: 'help'
            i 'Regexp include filter for methods', longOpt: 'include', args: 1, argName: 'filter'
            e 'Regexp exclude filter for methods', longOpt: 'exclude', args: 1, argName: 'filter'
            g 'Regexp to include all stacks with match in any frame', longOpt: 'grep', args: 1, argName: 'filter'
            a "Tool action. Valid choices: ${reportActions.keySet().join(', ')}", longOpt: 'action', args: 1, argName: 'action'
            o 'Output file', longOpt: 'output', args: 1, argName: 'file'
            w 'Width of flamegraph', longOpt: 'width', args: 1, argName: 'pixels'
            _ 'flamegraph.pl path', longOpt: 'flamegraph-command', args: 1, argName: 'cmd'
            s 'Sort frames', longOpt: 'sort'
            m 'Minimum number of samples', longOpt: 'min', args: 1, argName: 'value'
            _ 'Minimum samples sum taken at frame depth', longOpt: 'min-samples-frame-depth', args: 1, argName: 'value'
            d 'Duration of time window, splits output in to multiple files', longOpt: 'duration', args: 1, argName: 'seconds'
            f 'First window duration half of given duration', longOpt: 'first-split'
            r 'Process stacks in reverse order', longOpt: 'reverse'
            b 'Begin time', longOpt: 'begin', args: 1, argName: 'seconds'
            l 'Length of selected time', longOpt: 'length', args: 1, argName: 'seconds'
            c 'Cut off frame pattern', longOpt: 'cutoff', args: 1, argName: 'pattern'
            n 'Don\'t compress package names', longOpt: 'no-compress'
            _ 'Allocation flamegraph', longOpt: 'allocations'
            _ 'Allocation method', longOpt: 'allocation-method', args: 1, argName: 'method [size|count]'
            _ 'Exceptions flamegraph', longOpt: 'exceptions'
        }
        cli.usage = "jfr-report-tool [-${cli.options.options.opt.findAll { it }.sort().join('')}] [jfrFile]"

        def options = cli.parse(args)
        if (!options) {
            return
        }

        def arguments = options.arguments().findAll { it }
        if (options.h || !arguments) {
            cli.usage()
            println "Supported actions:"
            reportActions.each { String action, String description ->
                println "${action.padRight(33)}${description}"
            }
            return
        }

        def action = options.action ?: 'flameGraph'

        if (!reportActions.containsKey(action)) {
            println "Unknown action $action"
            System.exit(1)
        }

        def jfrReportTool = new JfrReportTool()
        if (options.allocations) {
            jfrReportTool.useAllocationFlameGraph(options.'allocation-method'?:'size')
        }
        if (options.exceptions) {
            jfrReportTool.useExceptionFlameGraph()
        }
        if (options.i) {
            jfrReportTool.includeFilter = Pattern.compile(options.i)
        }
        if (options.e) {
            jfrReportTool.excludeFilter = (options.e == 'none' || options.e == '') ? null : Pattern.compile(options.e)
        }
        if (options.g) {
            jfrReportTool.grepFilter = Pattern.compile(options.g)
        }
        if (options.w) {
            jfrReportTool.flameGraphWidth = options.w as int
        }
        if (options.'flamegraph-command') {
            jfrReportTool.flameGraphCommand = options.'flamegraph-command'
        }
        if (options.m) jfrReportTool.minimumSamples = options.m as int
        if (options.'min-samples-frame-depth') jfrReportTool.minimumSamplesFrameDepth = options.'min-samples-frame-depth' as int
        if (options.s) jfrReportTool.sortFrames = true
        if (options.d) {
            jfrReportTool.timeWindowDuration = options.d as int
        }
        if (options.r) {
            jfrReportTool.reverse = true
        }
        if (options.b) {
            jfrReportTool.begin = options.b as int
        }
        if (options.l) {
            jfrReportTool.length = options.l as int
        }
        if (options.f) {
            jfrReportTool.firstSplit = true
        }
        if (options.c) {
            jfrReportTool.cutOffFilter = Pattern.compile(options.c)
        }
        if (options.n) {
            jfrReportTool.compressPackageNames = false
        }

        Closure methodClosure = jfrReportTool.&"$action"
        def file = new File(arguments.first()).absoluteFile
        def outputFile = (options.o) ? new File(String.valueOf(options.o)).getAbsoluteFile() : new File(file.parentFile, file.name + "." + resolveExtension(action, jfrReportTool))
        def allFiles = []
        jfrReportTool.outputMessage = { File writtenFile ->
            println "Output in ${writtenFile}"
            println "URL ${writtenFile.canonicalFile.toURI().toURL()}"
            allFiles << writtenFile
        }
        println "Converting $file"
        try {
            def methodParams = [input    : file,
                                output   : outputFile,
                                arguments: arguments,
                                options  : options]
            if (methodClosure.maximumNumberOfParameters == 2) {
                if (methodClosure.parameterTypes[1] == Map) {
                    methodClosure(file, methodParams)
                } else {
                    methodClosure(file, outputFile)
                }
            } else if (methodClosure.maximumNumberOfParameters == 1) {
                if (methodClosure.parameterTypes[0] == Map) {
                    methodClosure(methodParams)
                } else {
                    methodClosure(file)
                }
            } else {
                println "Unsupported action"
            }
            if (allFiles.size() > 1) {
                println "Index is ${jfrReportTool.createIndexFile(allFiles).toURI().toURL()}"
            }
        } catch (Throwable t) {
            t.printStackTrace()
        }
    }

    def useExceptionFlameGraph() {
        filteredEventPaths = [EXCEPTION_THROW_EVENT_PATH, ERROR_THROW_EVENT_PATH] as Set
        filteredEventPaths.addAll(INFO_EVENT_PATHS)
        flameGraphType = FlameGraphType.EXCEPTIONS
        excludeFilter = null
        minimumSamples = 1
    }

    private static String resolveExtension(String action, JfrReportTool jfrReportTool) {
        def extension = DEFAULT_EXTENSION[action] ?: 'svg'
        if (jfrReportTool.flameGraphType != FlameGraphType.DEFAULT) {
            extension = jfrReportTool.flameGraphType.toString().toLowerCase() + '.' + extension
        }
        extension
    }

    def useAllocationFlameGraph(String method) {
        filteredEventPaths = [ALLOCATION_IN_TLAB_EVENT_PATH, ALLOCATION_OUTSIDE_TLAB_EVENT_PATH] as Set
        filteredEventPaths.addAll(INFO_EVENT_PATHS)
        flameGraphType = FlameGraphType.ALLOCATIONS
        excludeFilter = null
        minimumSamples = 1
        allocationMethod = AllocationMethod.valueOf(method.toUpperCase())
    }

    enum AllocationMethod {
        SIZE, COUNT
    }

    File createIndexFile(List<File> allFiles) {
        File firstFile = allFiles[0]
        File indexFile = new File(firstFile.getParentFile() ?: new File(''), firstFile.getName() + ".html")
        indexFile.withWriter { writer ->
            writer << """
<html>
<head><title>Index of generated files</title></head>
<body>
"""
            allFiles.each { file ->
                def fileurl = file.toURI().toURL()
                writer << "<a href='${fileurl}'>$file.name</a><br/>\n"
                if (file.name.endsWith(".svg")) {
                    writer << "<img src='${fileurl}' border=0 />\n"
                }
            }
            writer << "</body></html>\n"
        }
        indexFile
    }
}


