/*
 * Copyright 2018 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.hyperfoil.cli.commands;

import io.hyperfoil.api.config.Protocol;
import io.hyperfoil.api.http.HttpMethod;
import io.hyperfoil.api.statistics.StatisticsSummary;
import io.hyperfoil.cli.HyperfoilCli;
import io.hyperfoil.cli.context.HyperfoilCliContext;
import io.hyperfoil.cli.context.HyperfoilCommandInvocation;
import io.hyperfoil.api.config.BenchmarkBuilder;
import io.hyperfoil.api.config.PhaseBuilder;
import io.hyperfoil.cli.context.HyperfoilCommandInvocationProvider;
import io.hyperfoil.controller.Client;
import io.hyperfoil.controller.HistogramConverter;
import io.hyperfoil.client.RestClient;
import io.hyperfoil.controller.model.CustomStats;
import io.hyperfoil.controller.model.RequestStatisticsResponse;
import io.hyperfoil.controller.model.RequestStats;
import io.hyperfoil.core.handlers.ByteBufSizeRecorder;
import io.hyperfoil.core.impl.LocalBenchmarkData;
import io.hyperfoil.core.util.Util;

import org.HdrHistogram.AbstractHistogram;
import org.HdrHistogram.HistogramIterationValue;
import org.aesh.command.AeshCommandRuntimeBuilder;
import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.CommandRuntime;
import org.aesh.command.impl.registry.AeshCommandRegistryBuilder;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;
import org.aesh.command.option.OptionList;
import org.aesh.terminal.utils.ANSI;
import org.aesh.terminal.utils.Config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static io.hyperfoil.core.builders.StepCatalog.SC;
import static io.vertx.core.logging.LoggerFactory.LOGGER_DELEGATE_FACTORY_CLASS_NAME;


public class Wrk {

   //ignore logging when running in the console below severe
   static {
      Handler[] handlers = Logger.getLogger("").getHandlers();
      for (int index = 0; index < handlers.length; index++) {
         handlers[index].setLevel(Level.SEVERE);
      }
   }


   public static void main(String[] args) {

      //set logger impl
      System.setProperty(LOGGER_DELEGATE_FACTORY_CLASS_NAME, "io.vertx.core.logging.Log4j2LogDelegateFactory");

      try {
         AeshCommandRuntimeBuilder<HyperfoilCommandInvocation> runtime = AeshCommandRuntimeBuilder.builder();
         runtime.commandInvocationProvider(new HyperfoilCommandInvocationProvider(new HyperfoilCliContext()));
         AeshCommandRegistryBuilder<HyperfoilCommandInvocation> registry =
               AeshCommandRegistryBuilder.<HyperfoilCommandInvocation>builder()
                     .commands(StartLocal.class, WrkCommand.class, HyperfoilCli.ExitCommand.class);
         runtime.commandRegistry(registry.create());
         CommandRuntime<HyperfoilCommandInvocation> cr = runtime.build();
         cr.executeCommand("start-local");
         cr.executeCommand("wrk " + String.join(" ", args));
         cr.executeCommand("exit");
      } catch (Exception e) {
         System.out.println("Failed to execute command:" + e.getMessage());
         e.printStackTrace();
         //todo: should provide help info here, will be added in newer version of æsh
         //System.out.println(runtime.commandInfo("wrk"));
      }
   }

   @CommandDefinition(name = "wrk", description = "Runs a workload simluation against one endpoint using the same vm")
   public class WrkCommand implements Command<HyperfoilCommandInvocation> {
      @Option(shortName = 'c', description = "Total number of HTTP connections to keep open", required = true)
      int connections;

      @Option(shortName = 'd', description = "Duration of the test, e.g. 2s, 2m, 2h", required = true)
      String duration;

      @Option(shortName = 't', description = "Total number of threads to use.")
      int threads;

      @Option(shortName = 'R', description = "Work rate (throughput)", required = true)
      int rate;

      @Option(shortName = 's', description = "!!!NOT SUPPORTED: LuaJIT script")
      String script;

      @Option(shortName = 'h', hasValue = false, overrideRequired = true)
      boolean help;

      @OptionList(shortName = 'H', name = "header", description = "HTTP header to add to request, e.g. \"User-Agent: wrk\"")
      List<String> headers;

      @Option(description = "Print detailed latency statistics", hasValue = false)
      boolean latency;

      @Option(description = "Record a timeout if a response is not received within this amount of time.", defaultValue = "60s")
      String timeout;

      @Option(shortName = 'a', description = "Inline definition of agent executing the test. By default assuming non-clustered mode.")
      String agent;

      @Argument(description = "URL that should be accessed", required = true)
      String url;

      String path;
      String[][] parsedHeaders;

      @Override
      public CommandResult execute(HyperfoilCommandInvocation invocation) {
         if (help) {
            invocation.println(invocation.getHelpInfo("wrk"));
            return CommandResult.SUCCESS;
         }
         if (script != null) {
            invocation.println("Scripting is not supported at this moment.");
         }
         if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
         }
         URI uri;
         try {
            uri = new URI(url);
         } catch (URISyntaxException e) {
            invocation.println("Failed to parse URL: " + e.getMessage());
            return CommandResult.FAILURE;
         }
         path = uri.getPath();
         if (uri.getQuery() != null) {
            path = path + "?" + uri.getQuery();
         }
         if (uri.getFragment() != null) {
            path = path + "#" + uri.getFragment();
         }
         if (headers != null) {
            parsedHeaders = new String[headers.size()][];
            for (int i = 0; i < headers.size(); i++) {
               String h = headers.get(i);
               int colonIndex = h.indexOf(':');
               if (colonIndex < 0) {
                  invocation.println(String.format("Cannot parse header '%s', ignoring.", h));
                  continue;
               }
               String header = h.substring(0, colonIndex).trim();
               String value = h.substring(colonIndex + 1).trim();
               parsedHeaders[i] = new String[]{ header, value };
            }
         } else {
            parsedHeaders = null;
         }

         Protocol protocol = Protocol.fromScheme(uri.getScheme());
         // @formatter:off
         BenchmarkBuilder builder = new BenchmarkBuilder(null, new LocalBenchmarkData())
               .name("wrk")
               .http()
                  .protocol(protocol).host(uri.getHost()).port(protocol.portOrDefault(uri.getPort()))
                  .sharedConnections(connections)
               .endHttp()
               .threads(this.threads);
         // @formatter:on
         if (agent != null && !agent.isEmpty()) {
            builder.addAgent("wrk-agent", agent, null);
         }

         addPhase(builder, "calibration", "1s");
         addPhase(builder, "test", duration).startAfter("calibration").maxDuration(duration);

         RestClient client = invocation.context().client();
         if (client == null) {
            invocation.println("You're not connected to a controller; either " + ANSI.BOLD + "connect" + ANSI.BOLD_OFF
                  + " to running instance or use " + ANSI.BOLD + "start-local" + ANSI.BOLD_OFF
                  + " to start a controller in this VM");
            return CommandResult.FAILURE;
         }
         Client.BenchmarkRef benchmark = client.register(builder.build(), null);
         invocation.context().setServerBenchmark(benchmark);
         Client.RunRef run = benchmark.start(null);
         invocation.context().setServerRun(run);

         invocation.println("Running for " + duration + " test @ " + url);
         invocation.println(threads + " threads and " + connections + " connections");

         while (true) {
            RequestStatisticsResponse recent = run.statsRecent();
            if ("TERMINATED".equals(recent.status)) {
               break;
            }
            invocation.getShell().write(ANSI.CURSOR_START);
            invocation.getShell().write(ANSI.ERASE_WHOLE_LINE);
            recent.statistics.stream().filter(rs -> "test".equals(rs.phase)).forEach(rs -> {
               double durationSeconds = (rs.summary.endTime - rs.summary.startTime) / 1000d;
               invocation.print("Requests/sec: " + String.format("%.02f", rs.summary.requestCount / durationSeconds));
            });
            try {
               Thread.sleep(1000);
            } catch (InterruptedException e) {
               invocation.println("Interrupt received, trying to abort run...");
               run.kill();
            }
         }
         invocation.println(Config.getLineSeparator() + "benchmark finished");
         RequestStatisticsResponse total = run.statsTotal();
         Collection<CustomStats> custom = run.customStats().stream()
               .filter(cs -> cs.phase.equals("test")).collect(Collectors.toList());
         RequestStats testStats = total.statistics.stream().filter(rs -> "test".equals(rs.phase))
               .findFirst().orElseThrow(() -> new IllegalStateException("Missing stats for phase 'test'"));
         AbstractHistogram histogram = HistogramConverter.convert(run.histogram(testStats.phase, testStats.stepId, testStats.metric));
         printStats(testStats.summary, histogram, custom, invocation);
         return CommandResult.SUCCESS;
      }

      private PhaseBuilder<?> addPhase(BenchmarkBuilder benchmarkBuilder, String phase, String duration) {
         // prevent capturing WrkCommand in closure
         String[][] parsedHeaders = this.parsedHeaders;
         // @formatter:off
         return benchmarkBuilder.addPhase(phase).constantPerSec(rate)
               .duration(duration)
               .maxSessions(rate * 15)
               .scenario()
                  .initialSequence("request")
                     .step(SC).httpRequest(HttpMethod.GET)
                        .path(path)
                        .headerAppender((session, request) -> {
                           if (parsedHeaders != null) {
                              for (String[] header : parsedHeaders) {
                                 request.putHeader(header[0], header[1]);
                              }
                           }
                        })
                        .timeout(timeout)
                        .handler()
                           .rawBytesHandler(new ByteBufSizeRecorder("bytes"))
                        .endHandler()
                     .endStep()
                  .endSequence()
               .endScenario();
         // @formatter:on
      }

      private void printStats(StatisticsSummary stats, AbstractHistogram histogram, Collection<CustomStats> custom, CommandInvocation invocation) {
         long dataRead = custom.stream().filter(cs -> cs.customName.equals("bytes")).mapToLong(cs -> Long.parseLong(cs.value)).findFirst().orElse(0);
         double durationSeconds = (stats.endTime - stats.startTime) / 1000d;
         invocation.println("                  Avg     Stdev       Max");
         invocation.println("Latency:    " + Util.prettyPrintNanosFixed(stats.meanResponseTime) + " "
               + Util.prettyPrintNanosFixed((long) histogram.getStdDeviation()) + " " + Util.prettyPrintNanosFixed(stats.maxResponseTime));
         if (latency) {
            invocation.println("Latency Distribution");
            for (Map.Entry<Double, Long> entry : stats.percentileResponseTime.entrySet()) {
               invocation.println(String.format("%7.3f", entry.getKey()) + " " + Util.prettyPrintNanosFixed(entry.getValue()));
            }
            invocation.println("----------------------------------------------------------");
            invocation.println("Detailed Percentile Spectrum");
            invocation.println("    Value  Percentile  TotalCount  1/(1-Percentile)");
            for (HistogramIterationValue value : histogram.percentiles(5)) {
               invocation.println(Util.prettyPrintNanosFixed(value.getValueIteratedTo()) + " " + String.format("%9.5f%%  %10d  %15.2f",
                     value.getPercentile(), value.getTotalCountToThisValue(), 100 / (100 - value.getPercentile())));
            }
            invocation.println("----------------------------------------------------------");
         }
         invocation.println(stats.requestCount + " requests in " + durationSeconds + "s, " + Util.prettyPrintData(dataRead) + " read");
         invocation.println("Requests/sec: " + String.format("%.02f", stats.requestCount / durationSeconds));
         if (stats.connectFailureCount + stats.resetCount + stats.timeouts + stats.status_4xx + stats.status_5xx > 0) {
            invocation.println("Socket errors: connect " + stats.connectFailureCount + ", reset " + stats.resetCount + ", timeout " + stats.timeouts);
            invocation.println("Non-2xx or 3xx responses: " + stats.status_4xx + stats.status_5xx + stats.status_other);
         }
         invocation.println("Transfer/sec: " + Util.prettyPrintData(dataRead / durationSeconds));
      }

   }

}
