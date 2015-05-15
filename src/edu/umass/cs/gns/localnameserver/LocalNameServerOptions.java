/*
 * Copyright (C) 2015
 * University of Massachusetts
 * All Rights Reserved 
 *
 * Initial developer(s): Westy.
 */
package edu.umass.cs.gns.localnameserver;

import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import static edu.umass.cs.gns.clientsupport.Defs.HELP;
import edu.umass.cs.gns.main.GNS;
import static edu.umass.cs.gns.util.Logging.DEFAULTCONSOLELEVEL;
import static edu.umass.cs.gns.util.ParametersAndOptions.CONFIG_FILE;
import java.util.Map;
import java.util.logging.Level;

/**
 * The command line options for LocalNameServer.
 * 
 * @author westy
 */
public class LocalNameServerOptions {

  // If you change this list, change it below in getAllOptions as well.
  public static final String NS_FILE = "nsfile";
  public static final String PORT = "port";
  public static final String FILE_LOGGING_LEVEL = "fileLoggingLevel";
  public static final String CONSOLE_OUTPUT_LEVEL = "consoleOutputLevel";
  public static final String DEBUG = "debug";

  public static Options getAllOptions() {
    Option help = new Option(HELP, "Prints usage");
    Option configFile = new Option(CONFIG_FILE, true, "Configuration file with list of parameters and values (an alternative to using command-line options)");
    Option nsFile = new Option(NS_FILE, true, "File with node configuration of all name servers");
    Option port = new Option(PORT, true, "Port");
    Option fileLoggingLevel = new Option(FILE_LOGGING_LEVEL, true, "Verbosity level of log file. Should be one of SEVERE, WARNING, INFO, CONFIG, FINE, FINER, FINEST.");
    Option consoleOutputLevel = new Option(CONSOLE_OUTPUT_LEVEL, true, "Verbosity level of console output. Should be one of SEVERE, WARNING, INFO, CONFIG, FINE, FINER, FINEST.");
    Option debug = new Option(DEBUG, "Enables debugging output");
    
    Options commandLineOptions = new Options();
    commandLineOptions.addOption(configFile);
    commandLineOptions.addOption(help);
    commandLineOptions.addOption(nsFile);
    commandLineOptions.addOption(port);
    commandLineOptions.addOption(debug);
    commandLineOptions.addOption(fileLoggingLevel);
    commandLineOptions.addOption(consoleOutputLevel);

    return commandLineOptions;
  }

  private static boolean initialized = false;

  /**
   * Initializes global parameter options from command line and config file options
   * that are not handled elsewhere.
   *
   * @param allValues
   */
  public static synchronized void initializeFromOptions(Map<String, String> allValues) {
    if (initialized) {
      return;
    }

    initialized = true;
    if (allValues == null) {
      return;
    }

    if (allValues.containsKey(DEBUG)) {
      LocalNameServer.debuggingEnabled = allValues.containsKey(DEBUG);
      System.out.println("******** DEBUGGING IS ENABLED IN LOCAL NAME SERVER *********");
    }

    if (allValues.containsKey(CONSOLE_OUTPUT_LEVEL)) {
      String levelString = allValues.get(CONSOLE_OUTPUT_LEVEL);
      try {
        Level level = Level.parse(levelString);
        // until a better way comes along
        LocalNameServer.LOG.setLevel(level);
      } catch (Exception e) {
        LocalNameServer.LOG.setLevel(DEFAULTCONSOLELEVEL);
        System.out.println("Could not parse " + levelString
                + "; set LocalNameServer log level to default level " + DEFAULTCONSOLELEVEL);
      }
    }
  }
 
}
