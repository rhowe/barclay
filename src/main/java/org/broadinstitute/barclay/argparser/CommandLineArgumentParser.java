package org.broadinstitute.barclay.argparser;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.OptionSpecBuilder;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.text.WordUtils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.broadinstitute.barclay.utils.Utils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Annotation-driven utility for parsing command-line arguments, checking for errors, and producing usage message.
 * <p/>
 * This class supports arguments of the form -KEY VALUE, plus positional arguments.
 * <p/>
 * The caller must supply an object that both defines the command line and has the parsed arguments set into it.
 * For each possible "-KEY VALUE" argument, there must be a public data member annotated with @Argument.  The KEY name is
 * the name of the fullName attribute of @Argument.  An abbreviated name may also be specified with the shortName attribute
 * of @Argument.
 * If the data member is a List<T>, then the argument may be specified multiple times.  The type of the data member,
 * or the type of the List element must either have a ctor T(String), or must be an Enum.  List arguments must
 * be initialized by the caller with some kind of list.  Any other argument that is non-null is assumed to have the given
 * value as a default.  If an argument has no default value, and does not have the optional attribute of @Argument set,
 * is required.  For List arguments, minimum and maximum number of elements may be specified in the @Argument annotation.
 * <p/>
 * A single List data member may be annotated with the @PositionalArguments.  This behaves similarly to a Argument
 * with List data member: the caller must initialize the data member, the type must be constructable from String, and
 * min and max number of elements may be specified.  If no @PositionalArguments annotation appears in the object,
 * then it is an error for the command line to contain positional arguments.
 * <p/>
 * A single String public data member may be annotated with @Usage.  This string, if present, is used to
 * construct the usage message.  Details about the possible arguments are automatically appended to this string.
 * If @Usage does not appear, a boilerplate usage message is used.
 */
public final class CommandLineArgumentParser implements CommandLineParser {
    // For formatting argument section of usage message.
    private static final int ARGUMENT_COLUMN_WIDTH = 30;
    private static final int DESCRIPTION_COLUMN_WIDTH = 90;

    private static final String ENUM_OPTION_DOC_PREFIX = "Possible values: {";
    private static final String ENUM_OPTION_DOC_SUFFIX = "} ";

    // Use these if no @Usage annotation
    private static final String defaultUsagePreamble = "Usage: program [arguments...]\n";
    private static final String defaultUsagePreambleWithPositionalArguments =
            "Usage: program [arguments...] [positional-arguments...]\n";
    private static final String NULL_STRING = "null";
    public static final String COMMENT = "#";
    public static final String POSITIONAL_ARGUMENTS_NAME = "Positional Argument";

    private static final Logger logger = LogManager.getLogger();

    // Map from (full class) name of each CommandLinePluginDescriptor requested and
    // found to the actual descriptor instance
    private Map<String, CommandLinePluginDescriptor<?>> pluginDescriptors = new HashMap<>();

    // Return the plugin instance corresponding to the targetDescriptor class
    @Override
    public <T> T getPluginDescriptor(Class<T> targetDescriptor) {
        return targetDescriptor.cast(pluginDescriptors.get(targetDescriptor.getName()));
    }

    private final Set<String> argumentsFilesLoadedAlready = new LinkedHashSet<>();

    /**
     * A typical command line program will call this to get the beginning of the usage message,
     * and then append a description of the program, like this:
     * commandLineParser.getStandardUsagePreamble(getClass()) + "Frobnicates the freebozzle."
     */
    @Override
    public String getStandardUsagePreamble(final Class<?> mainClass) {
        return "USAGE: " + mainClass.getSimpleName() + " [arguments]\n\n";
    }


    private void putInArgumentMap(ArgumentDefinition arg){
        for (String key: arg.getNames()){
            argumentMap.put(key, arg);
        }
    }

    private boolean inArgumentMap(ArgumentDefinition arg){
        for (String key: arg.getNames()){
            if(argumentMap.containsKey(key)){
                return true;
            }
        }
        return false;
    }

    // This is the object that the caller has provided that contains annotations,
    // and into which the values will be assigned.
    private final Object callerArguments;


    // null if no @PositionalArguments annotation
    private Field positionalArguments;
    private int minPositionalArguments;
    private int maxPositionalArguments;
    private Object positionalArgumentsParent;

    // List of all the data members with @Argument annotation
    private List<ArgumentDefinition> argumentDefinitions = new ArrayList<>();

    // Maps long name, and short name, if present, to an argument definition that is
    // also in the argumentDefinitions list.
    private final Map<String, ArgumentDefinition> argumentMap = new LinkedHashMap<>();

    // In case implementation wants to get at arg for some reason.
    private String[] argv;


    // The associated program properties using the CommandLineProgramProperties annotation
    private final CommandLineProgramProperties programProperties;

    private String getUsagePreamble() {
        String usagePreamble = "";
        if (null != programProperties) {
            usagePreamble += programProperties.summary();
        } else if (positionalArguments == null) {
            usagePreamble += defaultUsagePreamble;
        } else {
            usagePreamble += defaultUsagePreambleWithPositionalArguments;
        }
        return usagePreamble;
    }

    /**
     * @param callerArguments The object containing the command line arguments to be populated by
     *                        this command line parser.
     */
    public CommandLineArgumentParser(final Object callerArguments) {
        this(callerArguments, new ArrayList<>());
    }

    /**
     * @param callerArguments The object containing the command line arguments to be populated by
     *                        this command line parser.
     * @param pluginDescriptors A list of {@link CommandLinePluginDescriptor} objects that
     *                          should be used by this command line parser to extend the list of
     *                          command line arguments with dynamically discovered plugins. If
     *                          null, no descriptors are loaded.
     */
    public CommandLineArgumentParser(
            final Object callerArguments,
            final List<? extends CommandLinePluginDescriptor<?>> pluginDescriptors) {
        Utils.nonNull(callerArguments, "The object with command line arguments cannot be null");
        Utils.nonNull(pluginDescriptors, "The list of pluginDescriptors cannot be null");

        this.callerArguments = callerArguments;

        createArgumentDefinitions(callerArguments, null);
        createCommandLinePluginArgumentDefinitions(pluginDescriptors);

        this.programProperties = this.callerArguments.getClass().getAnnotation(CommandLineProgramProperties.class);
    }

    private void createArgumentDefinitions(
            final Object callerArguments,
            final CommandLinePluginDescriptor<?> controllingDescriptor) {
        for (final Field field : CommandLineParser.getAllFields(callerArguments.getClass())) {
            if (field.getAnnotation(Argument.class) != null && field.getAnnotation(ArgumentCollection.class) != null){
                throw new CommandLineException.CommandLineParserInternalException("An Argument cannot be an argument collection: "
                        +field.getName() + " in " + callerArguments.toString() + " is annotated as both.");
            }
            if (field.getAnnotation(PositionalArguments.class) != null) {
                handlePositionalArgumentAnnotation(field, callerArguments);
            }
            if (field.getAnnotation(Argument.class) != null) {
                handleArgumentAnnotation(field, callerArguments, controllingDescriptor);
            }
            if (field.getAnnotation(ArgumentCollection.class) != null) {
                try {
                    field.setAccessible(true);
                    createArgumentDefinitions(field.get(callerArguments), controllingDescriptor);
                } catch (final IllegalAccessException e) {
                    throw new CommandLineException.ShouldNeverReachHereException("should never reach here because we setAccessible(true)", e);
                }
            }
        }
    }

    // Find all the instances of plugins specified by the provided plugin descriptors
    private void createCommandLinePluginArgumentDefinitions(
            final List<? extends CommandLinePluginDescriptor<?>> requestedPluginDescriptors) {
        // For each descriptor, create the argument definitions for the descriptor object itself,
        // then process it's plugin classes
        requestedPluginDescriptors.forEach(
                descriptor -> {
                    pluginDescriptors.put(descriptor.getClass().getName(), descriptor);
                    createArgumentDefinitions(descriptor, null);
                    findPluginsForDescriptor(descriptor);
                }
        );
    }

    // Find all of the classes that derive from the class specified by the descriptor, obtain an
    // instance each and add its ArgumentDefinitions
    private void findPluginsForDescriptor(
            final CommandLinePluginDescriptor<?> pluginDescriptor) {
        final ClassFinder classFinder = new ClassFinder();
        pluginDescriptor.getPackageNames().forEach(
                pkg -> classFinder.find(pkg, pluginDescriptor.getPluginClass()));
        final Set<Class<?>> pluginClasses = classFinder.getClasses();

        final List<Object> plugins = new ArrayList<>(pluginClasses.size());
        for (Class<?> c : pluginClasses) {
            if (pluginDescriptor.getClassFilter().test(c)) {
                try {
                    final Object plugin = pluginDescriptor.getInstance(c);
                    plugins.add(plugin);
                    createArgumentDefinitions(plugin, pluginDescriptor);
                } catch (InstantiationException | IllegalAccessException e) {
                    throw new CommandLineException.CommandLineParserInternalException("Problem making an instance of plugin " + c +
                            " Do check that the class has a non-arg constructor", e);
                }
            }
        }
    }

    /**
     * @return the list of ArgumentDefinitions seen by the parser
     */
    public List<ArgumentDefinition> getArgumentDefinitions() { return argumentDefinitions; }

    /**
     * @return the Field representing positional any argument definition found by the parser
     */
    public Field getPositionalArguments() { return positionalArguments; }

    @Override
    public String getVersion() {
        return "Version:" + this.callerArguments.getClass().getPackage().getImplementationVersion();
    }

    /**
     * Print a usage message based on the arguments object passed to the ctor.
     *
     * @param stream Where to write the usage message.
     * @param printCommon True if common args should be included in the usage message.
     */
    @Override
    public void usage(final PrintStream stream, final boolean printCommon) {
        stream.print(getStandardUsagePreamble(callerArguments.getClass()) + getUsagePreamble());
        stream.println("\n" + getVersion());

        // filter on common and partition on plugin-controlled
        final Map<Boolean, List<ArgumentDefinition>> allArgsMap = argumentDefinitions.stream()
                .filter(argumentDefinition -> printCommon || !argumentDefinition.isCommon)
                .collect(Collectors.partitioningBy(a -> a.controllingDescriptor == null));

        List<ArgumentDefinition> nonPluginArgs = allArgsMap.get(true);
        if (null != nonPluginArgs && !nonPluginArgs.isEmpty()) {
            // partition the non-plugin args on optional
            final Map<Boolean, List<ArgumentDefinition>> unconditionalArgsMap = nonPluginArgs.stream()
                    .collect(Collectors.partitioningBy(a -> a.optional));

            final List<ArgumentDefinition> reqArgs = unconditionalArgsMap.get(false); // required args
            if (reqArgs != null && !reqArgs.isEmpty()) {
                stream.println("\n\nRequired Arguments:\n");
                reqArgs.sort(ArgumentDefinition.sortByLongName);
                reqArgs.stream().forEach(argumentDefinition -> printArgumentUsage(stream, argumentDefinition));
            }

            final List<ArgumentDefinition> optArgs = unconditionalArgsMap.get(true); // optional args
            if (optArgs != null && !optArgs.isEmpty()) {
                stream.println("\nOptional Arguments:\n");
                optArgs.sort(ArgumentDefinition.sortByLongName);
                optArgs.stream().forEach(argumentDefinition -> printArgumentUsage(stream, argumentDefinition));
            }
        }

        // now the conditional/dependent args (those controlled by a plugin descriptor)
        List<ArgumentDefinition> conditionalArgs = allArgsMap.get(false);
        if (null != conditionalArgs && !conditionalArgs.isEmpty()) {
            // group all of the conditional argdefs by the name of their controlling pluginDescriptor class
            final Map<CommandLinePluginDescriptor<?>, List<ArgumentDefinition>> argsByControllingDescriptor =
                    conditionalArgs
                            .stream()
                            .collect(Collectors.groupingBy(argDef -> argDef.controllingDescriptor));

            // sort the list of controlling pluginDescriptors by display name and iterate through them
            final List<CommandLinePluginDescriptor<?>> pluginDescriptorSortedByName =
                    new ArrayList<>(argsByControllingDescriptor.keySet());
            pluginDescriptorSortedByName.sort(
                    (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.getDisplayName(), b.getDisplayName())
            );
            for (final CommandLinePluginDescriptor<?> descriptor: pluginDescriptorSortedByName) {
                stream.println("Conditional Arguments for " + descriptor.getDisplayName() + ":\n");
                // get all the argument definitions controlled by this pluginDescriptor's plugins, group
                // those by plugin, and get the sorted list of names of the owning plugins
                final Map<String, List<ArgumentDefinition>> byPlugin =
                        argsByControllingDescriptor.get(descriptor)
                                .stream()
                                .collect(Collectors.groupingBy(argDef -> argDef.parent.getClass().getSimpleName()));
                final List<String> sortedPluginNames = new ArrayList<>(byPlugin.keySet());
                sortedPluginNames.sort(String.CASE_INSENSITIVE_ORDER);

                // iterate over the owning plugins in sorted order, get each one's argdefs in sorted order,
                // and print their usage
                for (final String pluginName: sortedPluginNames) {
                    stream.println("Valid only if \"" + pluginName + "\" is specified:");
                    final List<ArgumentDefinition> pluginArgs = byPlugin.get(pluginName);
                    pluginArgs.sort(ArgumentDefinition.sortByLongName);
                    pluginArgs.forEach(argDef -> printArgumentUsage(stream, argDef));
                }
            }
        }
    }

    /**
     * Parse command-line arguments, and store values in callerArguments object passed to ctor.
     * @param messageStream Where to write error messages.
     * @param args          Command line tokens.
     * @return true if command line is valid and the program should run, false if help or version was requested
     * @throws CommandLineException if there is an invalid command line
     */
    @SuppressWarnings("unchecked")
    @Override
    public boolean parseArguments(final PrintStream messageStream, final String[] args) {
        this.argv = args;

        OptionParser parser = new OptionParser(false);

        for (ArgumentDefinition arg : argumentDefinitions){
            OptionSpecBuilder bld = parser.acceptsAll(arg.getNames(), arg.doc);
            if (arg.isFlag()) {
                bld.withOptionalArg().withValuesConvertedBy(new StrictBooleanConverter());
            } else {
                bld.withRequiredArg();
            }
        }
        if(positionalArguments != null){
            parser.nonOptions();
        }

        OptionSet parsedArguments;
        try {
            parsedArguments = parser.parse(args);
        } catch (final OptionException e) {
            throw new CommandLineException(e.getMessage());
        }
        //Check for the special arguments file flag
        //if it's seen, read arguments from that file and recursively call parseArguments()
        if (parsedArguments.has(SpecialArgumentsCollection.ARGUMENTS_FILE_FULLNAME)) {
            List<String> argfiles = parsedArguments.valuesOf(SpecialArgumentsCollection.ARGUMENTS_FILE_FULLNAME).stream()
                    .map(f -> (String)f)
                    .collect(Collectors.toList());

            List<String> newargs = argfiles.stream()
                    .distinct()
                    .filter(file -> !argumentsFilesLoadedAlready.contains(file))
                    .flatMap(file -> loadArgumentsFile(file).stream())
                    .collect(Collectors.toList());
            argumentsFilesLoadedAlready.addAll(argfiles);

            if (!newargs.isEmpty()) {
                newargs.addAll(Arrays.asList(args));
                return parseArguments(messageStream, newargs.toArray(new String[newargs.size()]));
            }
        }

        //check if special short circuiting arguments are set
        if (isSpecialFlagSet(parsedArguments, SpecialArgumentsCollection.HELP_FULLNAME)) {
            usage(messageStream, true);
            return false;
        } else if (isSpecialFlagSet(parsedArguments, SpecialArgumentsCollection.VERSION_FULLNAME)) {
            messageStream.println(getVersion());
            return false;
        }

        for (OptionSpec<?> optSpec : parsedArguments.asMap().keySet()) {
            if (parsedArguments.has(optSpec)) {
                ArgumentDefinition argDef = argumentMap.get(optSpec.options().get(0));
                setArgument(argDef, (List<String>) optSpec.values(parsedArguments));
            }
        }

        for (Object arg : parsedArguments.nonOptionArguments()) {
            setPositionalArgument((String) arg);
        }

        assertArgumentsAreValid();

        return true;
    }

    /**
     *  helper to deal with the case of special flags that are evaluated before the options are properly set
     */
    private boolean isSpecialFlagSet(OptionSet parsedArguments, String flagName){
        if (parsedArguments.has(flagName)){
            Object value = parsedArguments.valueOf(flagName);
            return  (value == null || !value.equals("false"));
        } else{
            return false;
        }

    }

    /**
     * After command line has been parsed, make sure that all required arguments have values, and that
     * lists with minimum # of elements have sufficient values.
     *
     * @throws CommandLineException if arguments requirements are not satisfied.
     */
    private void assertArgumentsAreValid()  {
        validatePluginArguments(); // trim the list of plugin-derived argument definitions before validation
        try {
            for (final ArgumentDefinition argumentDefinition : argumentDefinitions) {
                final String fullName = argumentDefinition.getLongName();
                final StringBuilder mutextArgumentNames = new StringBuilder();
                for (final String mutexArgument : argumentDefinition.mutuallyExclusive) {
                    final ArgumentDefinition mutextArgumentDef = argumentMap.get(mutexArgument);
                    if (mutextArgumentDef != null && mutextArgumentDef.hasBeenSet) {
                        mutextArgumentNames.append(" ").append(mutextArgumentDef.getLongName());
                    }
                }
                if (argumentDefinition.hasBeenSet && mutextArgumentNames.length() > 0) {
                    throw new CommandLineException("Argument '" + fullName +
                            "' cannot be used in conjunction with argument(s)" +
                            mutextArgumentNames.toString());
                }
                if (argumentDefinition.isCollection && !argumentDefinition.optional) {
                    @SuppressWarnings("rawtypes")
                    final Collection c = (Collection) argumentDefinition.getFieldValue();
                    if (c.isEmpty()) {
                        throw new CommandLineException.MissingArgument(fullName, "Argument '" + fullName + "' must be specified at least once.");
                    }
                } else if (!argumentDefinition.optional && !argumentDefinition.hasBeenSet && mutextArgumentNames.length() == 0) {
                    throw new CommandLineException.MissingArgument(fullName, "Argument '" + fullName + "' is required" +
                            (argumentDefinition.mutuallyExclusive.isEmpty() ? "." : " unless any of " + argumentDefinition.mutuallyExclusive +
                                    " are specified."));
                }
            }
            if (positionalArguments != null) {
                @SuppressWarnings("rawtypes")
                final Collection c = (Collection) positionalArguments.get(positionalArgumentsParent);
                if (c.size() < minPositionalArguments) {
                    throw new CommandLineException.MissingArgument(POSITIONAL_ARGUMENTS_NAME,"At least " + minPositionalArguments +
                            " positional arguments must be specified.");
                }
            }
        } catch (final IllegalAccessException e) {
            throw new CommandLineException.ShouldNeverReachHereException("Should never happen",e);
        }

    }

    // Once all command line args have been processed, go through the argument definitions and
    // validate any that are plugin class arguments against the controlling descriptor, trimming
    // the list of argument definitions along the way by removing any that have not been set
    // (so validation doesn't complain about missing required arguments for plugins that weren't
    // specified) and throwing for any that have been set but are not allowed. Note that we don't trim
    // the list of plugins themselves (just the argument definitions), since the plugin may contain
    // other arguments that require validation.
    private void validatePluginArguments() {
        final List<ArgumentDefinition> actualArgumentDefinitions = new ArrayList<>();
        for (final ArgumentDefinition argumentDefinition : argumentDefinitions) {
            if (!argumentDefinition.isControlledByPlugin()) {
                actualArgumentDefinitions.add(argumentDefinition);
            } else {
                final boolean isAllowed = argumentDefinition.controllingDescriptor.isDependentArgumentAllowed(
                        argumentDefinition.parent.getClass());
                if (argumentDefinition.hasBeenSet) {
                    if (!isAllowed) {
                        // dangling dependent argument; a value was specified but it's containing
                        // (predecessor) plugin argument wasn't specified
                        throw new CommandLineException(
                                String.format(
                                        "Argument \"%s/%s\" is only valid when the argument \"%s\" is specified",
                                        argumentDefinition.shortName,
                                        argumentDefinition.getLongName(),
                                        argumentDefinition.parent.getClass().getSimpleName()));
                    }
                    actualArgumentDefinitions.add(argumentDefinition);
                } else if (isAllowed) {
                    // the predecessor argument was seen, so this value is allowed but hasn't been set; keep the
                    // argument definition to allow validation to check for missing required args
                    actualArgumentDefinitions.add(argumentDefinition);
                }
            }
        }

        // update the list of argument definitions with the new list
        argumentDefinitions = actualArgumentDefinitions;

        // finally, give each plugin a chance to trim down any unseen instances from it's own list
        pluginDescriptors.entrySet().forEach(e -> e.getValue().validateArguments());
    }
    /**
     * Check the provided value against any range constraints specified in the Argument annotation
     * for the corresponding field. Throw an exception if limits are violated.
     *
     * - Only checks numeric types (int, double, etc.)
     */
    private void checkArgumentRange(final ArgumentDefinition argumentDefinition, final Object argumentValue) {
        // Only validate numeric types because we have already ensured at constructor time that only numeric types have bounds
        if (!Number.class.isAssignableFrom(argumentDefinition.type)) {
            return;
        }

        final Double argumentDoubleValue = (argumentValue == null) ? null : ((Number)argumentValue).doubleValue();

        // Check hard limits first, if specified
        if (isOutOfRange(argumentDefinition.minValue, argumentDefinition.maxValue, argumentDoubleValue)) {
            throw new CommandLineException.OutOfRangeArgumentValue(argumentDefinition.getLongName(), argumentDefinition.minValue, argumentDefinition.maxValue, argumentValue);
        }
        // Check recommended values
        if (isOutOfRange(argumentDefinition.minRecommendedValue, argumentDefinition.maxRecommendedValue, argumentDoubleValue)) {
            final boolean outMinValue = argumentDefinition.minRecommendedValue != Double.NEGATIVE_INFINITY;
            final boolean outMaxValue = argumentDefinition.maxRecommendedValue != Double.POSITIVE_INFINITY;
            if (outMinValue && outMaxValue) {
                logger.warn("Argument --{} has value {}, but recommended within range ({},{})",
                        argumentDefinition.getLongName(), argumentDoubleValue, argumentDefinition.minRecommendedValue, argumentDefinition.maxRecommendedValue);
            } else if (outMinValue) {
                logger.warn("Argument --{} has value {}, but minimum recommended is {}",
                        argumentDefinition.getLongName(), argumentDoubleValue, argumentDefinition.minRecommendedValue);
            } else if (outMaxValue) {
                logger.warn("Argument --{} has value {}, but maximum recommended is {}",
                        argumentDefinition.getLongName(), argumentDoubleValue, argumentDefinition.maxRecommendedValue);
            }
        }
    }

    // null values are always out of range
    private static boolean isOutOfRange(final double minValue, final double maxValue, final Double value) {
        return value == null || minValue != Double.NEGATIVE_INFINITY && value < minValue
                || maxValue != Double.POSITIVE_INFINITY && value > maxValue;
    }

    // check if the value is infinity or a mathematical integer
    private static boolean isInfinityOrMathematicalInteger(final double value) {
        return Double.isInfinite(value) || value == Math.rint(value);
    }


    @SuppressWarnings("unchecked")
    private void setPositionalArgument(final String stringValue) {
        if (positionalArguments == null) {
            throw new CommandLineException("Invalid argument '" + stringValue + "'.");
        }
        final Object value = constructFromString(CommandLineParser.getUnderlyingType(positionalArguments), stringValue, POSITIONAL_ARGUMENTS_NAME);
        @SuppressWarnings("rawtypes")
        final Collection c;
        try {
            c = (Collection) positionalArguments.get(callerArguments);
        } catch (final IllegalAccessException e) {
            throw new CommandLineException.ShouldNeverReachHereException(e);
        }
        if (c.size() >= maxPositionalArguments) {  //we're checking if there is space to add another argument
            throw new CommandLineException("No more than " + maxPositionalArguments +
                    " positional arguments may be specified on the command line.");
        }
        c.add(value);
    }

    @SuppressWarnings("unchecked")
    private void setArgument(ArgumentDefinition argumentDefinition, final List<String> values) {
        //special treatment for flags
        if (argumentDefinition.isFlag() && values.isEmpty()){
            argumentDefinition.hasBeenSet = true;
            argumentDefinition.setFieldValue(true);
            return;
        }

        if (!argumentDefinition.isCollection && (argumentDefinition.hasBeenSet || values.size() > 1)) {
            throw new CommandLineException("Argument '" + argumentDefinition.getNames() + "' cannot be specified more than once.");
        }
        if (argumentDefinition.isCollection) {
            // if this is a collection then we only want to clear it once at the beginning, before we process
            // any of the values
            @SuppressWarnings("rawtypes")
            final Collection c = (Collection) argumentDefinition.getFieldValue();
            c.clear();
        }

        for (String stringValue: values) {
            final Object value;
            if (stringValue.equals(NULL_STRING)) {
                if (argumentDefinition.isCollection && values.size() > 1) {
                    // multiple values for a collection were passed, and at least one of them is null
                    throw new CommandLineException("A value of \"null\" can only be provided for a collection if it is the only value provided. Multiple values were provided for: " + argumentDefinition.getNames() + ".");
                }
                //"null" is a special value that allows the user to override any default
                //value set for this arg
                if (argumentDefinition.optional) {
                    value = null;
                } else {
                    throw new CommandLineException("Non \"null\" value must be provided for '" + argumentDefinition.getNames() + "'.");
                }
            } else {
                value = constructFromString(CommandLineParser.getUnderlyingType(argumentDefinition.field), stringValue, argumentDefinition.getLongName());
            }

            // check the argument range
            checkArgumentRange(argumentDefinition, value);

            if (argumentDefinition.isCollection) {
                @SuppressWarnings("rawtypes")
                final Collection c = (Collection) argumentDefinition.getFieldValue();
                if (value == null) {
                    //user specified this arg=null which is interpreted as empty list
                    c.clear();
                } else {
                    c.add(value);
                }
                argumentDefinition.hasBeenSet = true;
            } else {
                argumentDefinition.setFieldValue(value);
                argumentDefinition.hasBeenSet = true;
            }
        }
    }

    /**
     * Read an argument file and return a list of the args contained in it
     * A line that starts with {@link #COMMENT}  is ignored.
     *
     * @param argumentsFile a text file containing args
     * @return false if a fatal error occurred
     */
    private List<String> loadArgumentsFile(final String argumentsFile) {
        List<String> args = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(argumentsFile))){
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(COMMENT) && !line.trim().isEmpty()) {
                    args.addAll(Arrays.asList(StringUtils.split(line)));
                }
            }
        } catch (final IOException e) {
            throw new CommandLineException("I/O error loading arguments file:" + argumentsFile, e);
        }
        return args;
    }

    private void printArgumentUsage(final PrintStream stream, final ArgumentDefinition argumentDefinition) {
        printArgumentParamUsage(stream, argumentDefinition.getLongName(), argumentDefinition.shortName,
                CommandLineParser.getUnderlyingType(argumentDefinition.field).getSimpleName(),
                makeArgumentDescription(argumentDefinition));
    }


    private void printArgumentParamUsage(final PrintStream stream, final String name, final String shortName,
                                         final String type, final String argumentDescription) {
        String argumentLabel = name;
        if (type != null) argumentLabel = "--"+ argumentLabel;

        if (!shortName.isEmpty()) {
            argumentLabel+=",-" + shortName;
        }
        argumentLabel += ":" + type;
        stream.print(argumentLabel);

        int numSpaces = ARGUMENT_COLUMN_WIDTH - argumentLabel.length();
        if (argumentLabel.length() > ARGUMENT_COLUMN_WIDTH) {
            stream.println();
            numSpaces = ARGUMENT_COLUMN_WIDTH;
        }
        printSpaces(stream, numSpaces);
        final String wrappedDescription = WordUtils.wrap(argumentDescription, DESCRIPTION_COLUMN_WIDTH);
        final String[] descriptionLines = wrappedDescription.split("\n");
        for (int i = 0; i < descriptionLines.length; ++i) {
            if (i > 0) {
                printSpaces(stream, ARGUMENT_COLUMN_WIDTH);
            }
            stream.println(descriptionLines[i]);
        }
        stream.println();
    }

    private String makeArgumentDescription(final ArgumentDefinition argumentDefinition) {
        final StringBuilder sb = new StringBuilder();
        if (!argumentDefinition.doc.isEmpty()) {
            sb.append(argumentDefinition.doc);
            sb.append("  ");
        }
        if (argumentDefinition.isCollection) {
            if (argumentDefinition.optional) {
                sb.append("This argument may be specified 0 or more times. ");
            } else {
                sb.append("This argument must be specified at least once. ");
            }
        }
        if (argumentDefinition.optional) {
            sb.append("Default value: ");
            sb.append(argumentDefinition.defaultValue);
            sb.append(". ");
        } else {
            sb.append("Required. ");
        }
        // if this argument definition is a string field contained within a plugin descriptor (i.e.,
        // it holds the names of plugins specified by the user on the command line, such as read filter names),
        // then we need to delegate to the plugin descriptor to generate the list of allowed values
        if (CommandLinePluginDescriptor.class.isAssignableFrom(argumentDefinition.parent.getClass()) &&
                CommandLineParser.getUnderlyingType(argumentDefinition.field).equals(String.class)) {
            usageForPluginDescriptorArgument(argumentDefinition, sb);
        } else {
            sb.append(getOptions(CommandLineParser.getUnderlyingType(argumentDefinition.field)));
        }
        if (!argumentDefinition.mutuallyExclusive.isEmpty()) {
            sb.append(" Cannot be used in conjuction with argument(s)");
            for (final String argument : argumentDefinition.mutuallyExclusive) {
                final ArgumentDefinition mutextArgumentDefinition = argumentMap.get(argument);

                if (mutextArgumentDefinition == null) {
                    throw new CommandLineException("Invalid argument definition in source code.  " + argument +
                            " doesn't match any known argument.");
                }
                sb.append(" ").append(mutextArgumentDefinition.fieldName);
                if (!mutextArgumentDefinition.shortName.isEmpty()) {
                    sb.append(" (").append(mutextArgumentDefinition.shortName).append(")");
                }
            }
        }
        return sb.toString();
    }

    private void usageForPluginDescriptorArgument(final ArgumentDefinition argDef, final StringBuilder sb) {
        final CommandLinePluginDescriptor<?> descriptor = (CommandLinePluginDescriptor<?>) argDef.parent;
        // this argument came from a plugin descriptor; delegate to get the list of allowed values
        final List<String> allowedValues = new ArrayList<>(descriptor.getAllowedValuesForDescriptorArgument(argDef.getLongName()));
        if (allowedValues.isEmpty()) {
            sb.append("Any value allowed");
        } else {
            allowedValues.sort(String.CASE_INSENSITIVE_ORDER);
            sb.append("Possible Values: {");
            sb.append(String.join(", ", allowedValues));
            sb.append("}");
        }
    }

    /**
     * Generates the option help string for a {@code boolean} or {@link Boolean} typed argument.
     * @return never {@code null}.
     */
    private String getBooleanOptions() {
        return String.format("%s%s, %s%s", ENUM_OPTION_DOC_PREFIX, Boolean.TRUE, Boolean.FALSE, ENUM_OPTION_DOC_SUFFIX);
    }

    /**
     * Composes the help string on the possible options an {@link Enum} typed argument can take.
     *
     * @param clazz target enum class. Assumed no to be {@code null}.
     * @param <T> enum class type.
     * @param <U> ClpEnum implementing version of <code>&lt;T&gt</code>;.
     * @throws CommandLineException if {@code &lt;T&gt;} has no constants.
     * @return never {@code null}.
     */
    private <T extends Enum<T>,U extends Enum<U> & ClpEnum> String getEnumOptions(final Class<T> clazz) {
        // We assume that clazz is guaranteed to be a Class<? extends Enum>, thus
        // getEnumConstants() won't ever return a null.
        final T[] enumConstants = clazz.getEnumConstants();
        if (enumConstants.length == 0) {
            throw new CommandLineException(String.format("Bad argument enum type '%s' with no options", clazz.getName()));
        }

        if (ClpEnum.class.isAssignableFrom(clazz)) {
            @SuppressWarnings("unchecked")
            final U[] clpEnumCastedConstants = (U[]) enumConstants;
            return getEnumOptionsWithDescription(clpEnumCastedConstants);
        } else {
            return getEnumOptionsWithoutDescription(enumConstants);
        }
    }

    /**
     * Composes the help string for enum options that do not provide additional help documentation.
     * @param enumConstants the enum constants. Assumed non-null.
     * @param <T> the enum type.
     * @return never {@code null}.
     */
    private <T extends Enum<T>> String getEnumOptionsWithoutDescription(final T[] enumConstants) {
        return Stream.of(enumConstants)
                .map(T::name)
                .collect(Collectors.joining(", ",ENUM_OPTION_DOC_PREFIX,ENUM_OPTION_DOC_SUFFIX));
    }

    /**
     * Composes the help string for enum options that provide additional documentation.
     * @param enumConstants the enum constants. Assumed non-null.
     * @param <T> the enum type.
     * @return never {@code null}.
     */
    private <T extends Enum<T> & ClpEnum> String getEnumOptionsWithDescription(final T[] enumConstants) {
        final String optionsString = Stream.of(enumConstants)
                .map(c -> String.format("%s (%s)",c.name(),c.getHelpDoc()))
                .collect(Collectors.joining("\n"));
        return String.join("\n",ENUM_OPTION_DOC_PREFIX,optionsString,ENUM_OPTION_DOC_SUFFIX);
    }

    /**
     * Returns the help string with details about valid options for the given argument class.
     *
     * <p>
     *     Currently this only make sense with {@link Boolean} and {@link Enum}. Any other class
     *     will result in an empty string.
     * </p>
     *
     * @param clazz the target argument's class.
     * @return never {@code null}.
     */
    @SuppressWarnings({"unchecked","rawtypes"})
    private String getOptions(final Class<?> clazz) {
        if (clazz == Boolean.class) {
            return getBooleanOptions();
        } else if (clazz.isEnum()) {
            final Class<? extends Enum> enumClass = (Class<? extends Enum>)clazz;
            return getEnumOptions(enumClass);
        } else {
            return "";
        }
    }

    private void printSpaces(final PrintStream stream, final int numSpaces) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < numSpaces; ++i) {
            sb.append(" ");
        }
        stream.print(sb);
    }

    private void handleArgumentAnnotation(
            final Field field, final Object parent, final CommandLinePluginDescriptor<?> controllingDescriptor) {
        try {
            field.setAccessible(true);
            final Argument argumentAnnotation = field.getAnnotation(Argument.class);
            final boolean isCollection = isCollectionField(field);
            if (isCollection) {
                field.setAccessible(true);
                if (field.get(parent) == null) {
                    createCollection(field, parent, "@Argument");
                }
            }
            if (!canBeMadeFromString(CommandLineParser.getUnderlyingType(field))) {
                throw new CommandLineException.CommandLineParserInternalException("@Argument member \"" + field.getName() +
                        "\" must have a String constructor or be an enum");
            }

            final ArgumentDefinition argumentDefinition = new ArgumentDefinition(field, argumentAnnotation, parent, controllingDescriptor);

            for (final String argument : argumentAnnotation.mutex()) {
                final ArgumentDefinition mutextArgumentDef = argumentMap.get(argument);
                if (mutextArgumentDef != null) {
                    mutextArgumentDef.mutuallyExclusive.add(getArgumentNameForMutex(field, argumentAnnotation));
                }
            }
            if (inArgumentMap(argumentDefinition)) {
                throw new CommandLineException.CommandLineParserInternalException(argumentDefinition.getNames() + " has already been used.");
            } else {
                putInArgumentMap(argumentDefinition);
                argumentDefinitions.add(argumentDefinition);
            }
        } catch (final IllegalAccessException e) {
            throw new CommandLineException.ShouldNeverReachHereException("We should not have reached here because we set accessible to true", e);
        }
    }

    private String getArgumentNameForMutex(final Field field, final Argument argumentAnnotation) {
        if (!argumentAnnotation.fullName().isEmpty()) {
            return argumentAnnotation.fullName();
        } else if (!argumentAnnotation.shortName().isEmpty()) {
            return argumentAnnotation.shortName();
        } else {
            return field.getName();
        }
    }

    private void handlePositionalArgumentAnnotation(final Field field, Object parent) {
        if (positionalArguments != null) {
            throw new CommandLineException.CommandLineParserInternalException
                    ("@PositionalArguments cannot be used more than once in an argument class.");
        }
        field.setAccessible(true);
        positionalArguments = field;
        positionalArgumentsParent = parent;
        if (!isCollectionField(field)) {
            throw new CommandLineException.CommandLineParserInternalException("@PositionalArguments must be applied to a Collection");
        }

        if (!canBeMadeFromString(CommandLineParser.getUnderlyingType(field))) {
            throw new CommandLineException.CommandLineParserInternalException("@PositionalParameters member " + field.getName() +
                    "does not have a String ctor");
        }

        final PositionalArguments positionalArgumentsAnnotation = field.getAnnotation(PositionalArguments.class);
        minPositionalArguments = positionalArgumentsAnnotation.minElements();
        maxPositionalArguments = positionalArgumentsAnnotation.maxElements();
        if (minPositionalArguments > maxPositionalArguments) {
            throw new CommandLineException.CommandLineParserInternalException("In @PositionalArguments, minElements cannot be > maxElements");
        }
        try {
            field.setAccessible(true);
            if (field.get(parent) == null) {
                createCollection(field, parent, "@PositionalParameters");
            }
        } catch (final IllegalAccessException e) {
            throw new CommandLineException.ShouldNeverReachHereException("We should not have reached here because we set accessible to true", e);

        }
    }


    private static boolean isCollectionField(final Field field) {
        try {
            field.getType().asSubclass(Collection.class);
            return true;
        } catch (final ClassCastException e) {
            return false;
        }
    }

    private void createCollection(final Field field, final Object callerArguments, final String annotationType)
            throws IllegalAccessException {
        try {
            field.set(callerArguments, field.getType().newInstance());
        } catch (final Exception ex) {
            try {
                field.set(callerArguments, new ArrayList<>());
            } catch (final IllegalArgumentException e) {
                throw new CommandLineException.CommandLineParserInternalException("In collection " + annotationType +
                        " member " + field.getName() +
                        " cannot be constructed or auto-initialized with ArrayList, so collection must be initialized explicitly.");
            }

        }

    }

    // True if clazz is an enum, or if it has a ctor that takes a single String argument.
    private boolean canBeMadeFromString(final Class<?> clazz) {
        if (clazz.isEnum()) {
            return true;
        }
        try {
            // Need to use getDeclaredConstructor() instead of getConstructor() in case the constructor
            // is non-public
            clazz.getDeclaredConstructor(String.class);
            return true;
        } catch (final NoSuchMethodException e) {
            return false;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object constructFromString(final Class clazz, final String s, final String argumentName) {
        try {
            if (clazz.isEnum()) {
                try {
                    return Enum.valueOf(clazz, s);
                } catch (final IllegalArgumentException e) {
                    throw new CommandLineException.BadArgumentValue(argumentName, s, "'" + s + "' is not a valid value for " +
                            clazz.getSimpleName() + ". "+ getEnumOptions(clazz) );
                }
            }
            // Need to use getDeclaredConstructor() instead of getConstructor() in case the constructor
            // is non-public. Set it to be accessible if it isn't already.
            final Constructor<?> ctor = clazz.getDeclaredConstructor(String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(s);
        } catch (final NoSuchMethodException e) {
            // Shouldn't happen because we've checked for presence of ctor
            throw new CommandLineException.ShouldNeverReachHereException("Cannot find string ctor for " + clazz.getName(), e);
        } catch (final InstantiationException e) {
            throw new CommandLineException.CommandLineParserInternalException("Abstract class '" + clazz.getSimpleName() +
                    "'cannot be used for an argument value type.", e);
        } catch (final IllegalAccessException e) {
            throw new CommandLineException.CommandLineParserInternalException("String constructor for argument value type '" + clazz.getSimpleName() +
                    "' must be public.", e);
        } catch (final InvocationTargetException e) {
            throw new CommandLineException.BadArgumentValue(argumentName, s, "Problem constructing " + clazz.getSimpleName() +
                    " from the string '" + s + "'.");
        }
    }

    public static class ArgumentDefinition {
        public final Field field;
        public final Class<?> type;
        final String fieldName;
        public final String fullName;
        public final String shortName;
        public final String doc;
        public final boolean optional;
        final boolean isCollection;
        public final String defaultValue;
        public final boolean isCommon;
        boolean hasBeenSet = false;
        public final Set<String> mutuallyExclusive;
        public final Object parent;
        final boolean isSpecial;
        final boolean isSensitive;
        public final CommandLinePluginDescriptor<?> controllingDescriptor;
        final Double maxValue;
        final Double minValue;
        final Double maxRecommendedValue;
        final Double minRecommendedValue;

        public ArgumentDefinition(
                final Field field,
                final Argument annotation,
                final Object parent,
                final CommandLinePluginDescriptor<?> controllingDescriptor) {
            this.field = field;
            this.fieldName = field.getName();
            this.parent = parent;
            this.fullName = annotation.fullName();
            this.shortName = annotation.shortName();
            this.doc = annotation.doc();
            this.isCollection = isCollectionField(field);

            this.isCommon = annotation.common();
            this.isSpecial = annotation.special();
            this.isSensitive = annotation.sensitive();

            this.mutuallyExclusive = new LinkedHashSet<>(Arrays.asList(annotation.mutex()));
            this.controllingDescriptor = controllingDescriptor;

            Object tmpDefault = getFieldValue();
            if (tmpDefault != null) {
                if (isCollection && ((Collection) tmpDefault).isEmpty()) {
                    //treat empty collections the same as uninitialized primitive types
                    this.defaultValue = NULL_STRING;
                } else {
                    //this is an initialized primitive type or a non-empty collection
                    this.defaultValue = tmpDefault.toString();
                }
            } else {
                this.defaultValue = NULL_STRING;
            }

            //null collections have been initialized by createCollection which is called in handleArgumentAnnotation
            //this is optional if it's specified as being optional or if there is a default value specified
            this.optional = annotation.optional() || ! this.defaultValue.equals(NULL_STRING);
            this.maxValue = annotation.maxValue();
            this.minValue = annotation.minValue();
            this.maxRecommendedValue = annotation.maxRecommendedValue();
            this.minRecommendedValue = annotation.minRecommendedValue();
            // bounds should be only set for numeric arguments and if the type is integer it should
            // be set to an integer
            this.type = CommandLineParser.getUnderlyingType(this.field);
            if (! Number.class.isAssignableFrom(this.type)) {
                if (this.maxValue != Double.POSITIVE_INFINITY
                        || this.minValue != Double.NEGATIVE_INFINITY
                        || this.maxRecommendedValue != Double.POSITIVE_INFINITY
                        || this.minRecommendedValue != Double.NEGATIVE_INFINITY) {
                    throw new CommandLineException.CommandLineParserInternalException(String.format("Min/max value ranges can only be set for numeric arguments. Argument --%s has a minimum or maximum value but has a non-numeric type.", this.getLongName()));
                }
            }
            if (Integer.class.isAssignableFrom(this.type)) {
                if (!isInfinityOrMathematicalInteger(this.maxValue)
                        || !isInfinityOrMathematicalInteger(this.minValue)
                        || !isInfinityOrMathematicalInteger(this.maxRecommendedValue)
                        || !isInfinityOrMathematicalInteger(this.minRecommendedValue)) {
                    throw new CommandLineException.CommandLineParserInternalException(String.format("Integer argument --%s has a minimum or maximum attribute with a non-integral value.", this.getLongName()));
                }
            }
        }

        public Object getFieldValue(){
            try {
                field.setAccessible(true);
                return field.get(parent);
            } catch (IllegalAccessException e) {
                throw new CommandLineException.ShouldNeverReachHereException("This shouldn't happen since we setAccessible(true).", e);
            }
        }

        public void setFieldValue(final Object value){
            try {
                field.setAccessible(true);
                field.set(parent, value);
            } catch (IllegalAccessException e) {
                throw new CommandLineException.ShouldNeverReachHereException("BUG: couldn't set field value. For "
                        + fieldName +" in " + parent.toString() + " with value " + value.toString()
                        + " This shouldn't happen since we setAccessible(true)", e);
            }
        }

        public boolean isFlag(){
            return field.getType().equals(boolean.class) || field.getType().equals(Boolean.class);
        }

        /**
         * Determine if this argument definition is controlled by a plugin (and thus subject to
         * descriptor dependency validation).
         * @return
         */
        public boolean isControlledByPlugin() { return controllingDescriptor != null; }

        public List<String> getNames(){
            List<String> names = new ArrayList<>();
            if (!shortName.isEmpty()){
                names.add(shortName);
            }
            if (!fullName.isEmpty()){
                names.add(fullName);
            } else {
                names.add(fieldName);
            }
            return names;
        }

        public String getLongName(){
            return !fullName.isEmpty() ? fullName : fieldName;
        }

        /**
         * Comparator for sorting ArgumentDefinitions in alphabetical order b y longName
         */
        public static Comparator<ArgumentDefinition> sortByLongName = new Comparator<ArgumentDefinition>() {
            public int compare(ArgumentDefinition argDef1, ArgumentDefinition argDef2) {
                return String.CASE_INSENSITIVE_ORDER.compare(argDef1.getLongName(), argDef2.getLongName());
            }
        };

        /**
         * Helper for pretty printing this option.
         * @param value A value this argument was given
         * @return a string
         *
         */
        private String prettyNameValue(Object value) {
            if(value != null){
                if (isSensitive){
                    return String.format("--%s ***********", getLongName());
                } else {
                    return String.format("--%s %s", getLongName(), value);
                }
            }
            return "";
        }

        /**
         * @return A string representation of this argument and it's value(s) which would be valid if copied and pasted
         * back as a command line argument
         */
        public String toCommandLineString(){
            Object value = getFieldValue();
            if (this.isCollection){
                Collection<?> collect = (Collection<?>)value;
                return collect.stream()
                        .map(this::prettyNameValue)
                        .collect(Collectors.joining(" "));

            } else {
                return prettyNameValue(value);
            }
        }

    }

    /**
     * The commandline used to run this program, including any default args that
     * weren't necessarily specified. This is used for logging and debugging.
     * <p/>
     * NOTE: {@link #parseArguments(PrintStream, String[])} must be called before
     * calling this method.
     *
     * @return The commandline, or null if {@link #parseArguments(PrintStream, String[])}
     * hasn't yet been called, or didn't complete successfully.
     */
    @SuppressWarnings("unchecked")
    @Override
    public String getCommandLine() {
        final String toolName = callerArguments.getClass().getName();
        final StringBuilder commandLineString = new StringBuilder();

        final List<Object> positionalArgs;
        if( positionalArguments != null) {
            try {
                positionalArguments.setAccessible(true);
                positionalArgs = (List<Object>) positionalArguments.get(positionalArgumentsParent);
            } catch (IllegalAccessException e) {
                throw new CommandLineException.ShouldNeverReachHereException("Should never reach here because we setAccessible(true)", e);
            }
            for (final Object posArg : positionalArgs) {
                commandLineString.append(" ").append(posArg.toString());
            }
        }

        //first, append args that were explicitly set
        commandLineString.append(argumentDefinitions.stream()
                .filter(argumentDefinition -> argumentDefinition.hasBeenSet)
                .map(ArgumentDefinition::toCommandLineString)
                .collect(Collectors.joining(" ", " ", "  ")))
                //next, append args that weren't explicitly set, but have a default value
                .append(argumentDefinitions.stream()
                        .filter(argumentDefinition -> !argumentDefinition.hasBeenSet && !argumentDefinition.defaultValue.equals(NULL_STRING))
                        .map(ArgumentDefinition::toCommandLineString)
                        .collect(Collectors.joining(" ")));

        return toolName + " " + commandLineString.toString();
    }

}