package storm.benchmark.metrics;

import backtype.storm.Config;
import backtype.storm.generated.*;
import backtype.storm.utils.NimbusClient;
import backtype.storm.utils.Utils;
import org.apache.log4j.Logger;
import storm.benchmark.BenchmarkConfig;
import storm.benchmark.util.BenchmarkUtils;
import storm.benchmark.util.FileUtils;
import storm.benchmark.util.MetricsUtils;

import java.io.PrintWriter;
import java.util.*;

public class StormMetrics implements IMetrics {
  private static final Logger LOG = Logger.getLogger(StormMetrics.class);

  public static final String METRICS_POLL_FREQ = "metrics.poll";
  public static final String METRICS_TOTAL_TIME = "metrics.time";
  public static final String METRICS_PATH = "metrics.path";

  /* headers */
  public static final String TIME = "time(s)";
  public static final String TIME_FORMAT = "%d";
  public static final String TOTAl_SLOTS = "total_slots";
  public static final String USED_SLOTS = "used_slots";
  public static final String WORKERS = "workers";
  public static final String TASKS = "tasks";
  public static final String EXECUTORS = "total_executors";
  public static final String EXECUTORS_METRICS = "executors_with_metrics";
  public static final String TRANSFERRED = "overall_transferred (messages)";
  public static final String THROUGHPUT = "overall_throughput (messages/s)";
  public static final String THROUGHPUT_MB = "overall_throughput (MB/s)";
  public static final String THROUGHPUT_MB_FORMAT = "%.1f";
  public static final String SPOUT_EXECUTORS = "spout_executors";
  public static final String SPOUT_TRANSFERRED = "spout_transferred (messages)";
  public static final String SPOUT_ACKED = "spout_acked (messages)";
  public static final String SPOUT_THROUGHPUT = "spout_throughput (messages/s)";
  public static final String SPOUT_THROUGHPUT_MB = "spout_throughput (MB/s)";
  public static final String SPOUT_THROUGHPUT_MB_FORMAT = "%.3f";


  public static final String ALL_TIME = ":all-time";
  public static final String LAST_TEN_MINS = "600";
  public static final String LAST_THREE_HOURS = "10800";
  public static final String LAST_DAY = "86400";

  public static final String METRICS_CONF_FORMAT = "%s/%s_metrics_%d.conf";
  public static final String METRICS_FILE_FORMAT = "%s/%s_metrics_%d.csv";

  public static final String SPOUT_AVG_LATENCY_FORMAT = "%.1f";
  public static final String SPOUT_MAX_LATENCY_FORMAT = "%.1f";

  public static final int DEFAULT_POLL_INTERVAL = 30 * 1000; // 30 secs
  public static final int DEFAULT_TOTAL_TIME =  5 * 60 * 1000; // 5 mins
  public static final int DEFAULT_MESSAGE_SIZE = 100; // 100 bytes
  public static final String DEFAULT_PATH = "/root/";

  // How often should metrics be collected
  int pollInterval;
  // How long should the benchmark run for
  int totalTime;
  // message size
  int msgSize;
  // metrics file path
  String path;

  Map config;
  StormTopology topology;
  String topoName;
  List<String> header = new LinkedList<String>();
  Map<String, String> metrics = new HashMap<String, String>();

  @Override
  public IMetrics setConfig(BenchmarkConfig benchConfig) {
    config = Utils.readStormConfig();
    topoName = (String) Utils.get(config, Config.TOPOLOGY_NAME, BenchmarkConfig.DEFAULT_TOPOLOGY_NAME);
    pollInterval = BenchmarkUtils.getInt(config, METRICS_POLL_FREQ, DEFAULT_POLL_INTERVAL);
    totalTime = BenchmarkUtils.getInt(config, METRICS_TOTAL_TIME, DEFAULT_TOTAL_TIME);
    msgSize = BenchmarkUtils.getInt(config, BenchmarkConfig.MESSAGE_SIZE, DEFAULT_MESSAGE_SIZE);
    path = (String) Utils.get(config, METRICS_PATH, DEFAULT_PATH);

    return this;
  }

  @Override
  public IMetrics setTopology(StormTopology topology) {
    this.topology = topology;
    return this;
  }

  @Override
  public IMetrics start() {
    long now = System.currentTimeMillis();
    long endTime = now + totalTime;
    MetricsState state = new MetricsState();
    state.startTime = now;
    state.lastTime = now;

    final String confFile = String.format(METRICS_CONF_FORMAT, path, topoName, now);
    final String dataFile = String.format(METRICS_FILE_FORMAT, path, topoName, now);
    PrintWriter confWriter = FileUtils.createFileWriter(confFile);
    PrintWriter dataWriter = FileUtils.createFileWriter(dataFile);
    writeStormConfig(confWriter);
    addHeaders();
    Nimbus.Client client = getNimbusClient();

    try {
      boolean live = true;
      while (live && now < endTime) {
        live = pollNimbus(client, now, state, dataWriter);
        Utils.sleep(pollInterval);
        now = System.currentTimeMillis();
      }
    } catch (Exception e) {
      LOG.error("storm metrics failed! ", e);
    } finally {
      dataWriter.close();
      confWriter.close();
    }
    return this;
  }

  public Nimbus.Client getNimbusClient() {
    return NimbusClient.getConfiguredClient(config).getClient();
  }

  public void writeStormConfig(PrintWriter writer) {
    LOG.info("writing out storm config into .conf file");
    if (writer != null) {
      Map sorted = new TreeMap();
      sorted.putAll(config);
      for (Object key : sorted.keySet()) {
        writer.println(key + "=" + config.get(key));
      }
      writer.flush();
    }
  }

  boolean pollNimbus(Nimbus.Client client, long now, MetricsState state, PrintWriter writer)
          throws Exception {
    ClusterSummary cs = client.getClusterInfo();
    if (null == cs) {
      LOG.error("ClusterSummary not found");
      return false;
    }

    updateSupervisorStats(cs);

    TopologySummary ts = MetricsUtils.getTopologySummary(cs, topoName);
    if (null == ts) {
      LOG.error("TopologySummary not found for " + topoName);
      return false;
    }

    updateTopologyStats(ts, state, now);
    TopologyInfo info = client.getTopologyInfo(ts.get_id());
    boolean firstTime = (now == state.startTime);
    updateExecutorStats(info, state, now, firstTime);

    if (firstTime) {
      writeHeader(writer);
    }

    writeLine(writer);
    state.lastTime = now;

    return true;
  }

  void updateTopologyStats(TopologySummary ts, MetricsState state, long now) {
    long timeTotal = now - state.startTime;
    int numWorkers = ts.get_num_workers();
    int numExecutors = ts.get_num_executors();
    int numTasks = ts.get_num_tasks();
    metrics.put(TIME, String.format(TIME_FORMAT, timeTotal / 1000));
    metrics.put(WORKERS, Integer.toString(numWorkers));
    metrics.put(EXECUTORS, Integer.toString(numExecutors));
    metrics.put(TASKS, Integer.toString(numTasks));
  }

  void updateSupervisorStats(ClusterSummary cs) {
    int totalSlots = 0;
    int usedSlots = 0;
    for (SupervisorSummary ss : cs.get_supervisors()) {
      totalSlots += ss.get_num_workers();
      usedSlots += ss.get_num_used_workers();
    }
    metrics.put(TOTAl_SLOTS, Integer.toString(totalSlots));
    metrics.put(USED_SLOTS, Integer.toString(usedSlots));
  }

  void updateExecutorStats(TopologyInfo info, MetricsState state, long now, boolean firstTime) {
    long overallTransferred = 0;
    long spoutTransferred = 0;
    long spoutAcked = 0;
    int executorsWithMetircs = 0;
    int spoutExecutors = 0;

    Map<String, List<Double>> comLat = new HashMap<String, List<Double>>();
    for (ExecutorSummary es : info.get_executors()) {
      String id = es.get_component_id();
      LOG.debug("get stats for component: " + id);
      if (Utils.isSystemId(id)) {
        LOG.debug("skip system component: " + id);
        continue;
      }
      ExecutorStats exeStats = es.get_stats();
      if (exeStats != null) {
        ExecutorSpecificStats specs = exeStats.get_specific();
        ComponentCommon common = Utils.getComponentCommon(topology, id);
        for (String stream : common.get_streams().keySet()) {
          LOG.debug("get stream " + stream + " for component " + id);
          executorsWithMetircs++;
          long transferred = MetricsUtils.getTransferred(exeStats, ALL_TIME, stream);
          overallTransferred += transferred;
          if (isSpout(specs)) {
            if (isDefaultStream(stream) || isBatchStream(stream)) {
              spoutExecutors++;
              spoutTransferred += transferred;
              SpoutStats spStats = specs.get_spout();
              spoutAcked += MetricsUtils.getSpoutAcked(spStats, ALL_TIME, stream);
              // first time
              if (firstTime) {
                header.add(MetricsUtils.getSpoutAvgCompleteLatencyTitle(id));
              }
              double lat = MetricsUtils.getSpoutCompleteLatency(spStats, ALL_TIME, stream);
              MetricsUtils.addLatency(comLat, id, lat);

            }
          }
        }
      } else {
        LOG.error("executor stats not found for component id: " + id );
      }
    }
    for (String id : comLat.keySet()) {
      List<Double> latList = comLat.get(id);
      double avg = null == latList ? 0.0 : BenchmarkUtils.avg(latList);
      double max = null == latList ? 0.0 : BenchmarkUtils.max(latList);
      metrics.put(MetricsUtils.getSpoutAvgCompleteLatencyTitle(id),
              String.format(SPOUT_AVG_LATENCY_FORMAT, avg));
      metrics.put(MetricsUtils.getSpoutMaxCompleteLatencyTitle(id),
              String.format(SPOUT_MAX_LATENCY_FORMAT, max));

    }
    metrics.put(EXECUTORS_METRICS, Integer.toString(executorsWithMetircs));
    metrics.put(SPOUT_EXECUTORS, Integer.toString(spoutExecutors));

    long timeDiff = now - state.lastTime;
    long overallDiff = overallTransferred - state.overallTransferred;
    long spoutDiff = spoutTransferred - state.spoutTransferred;
    long throughput = (long) MetricsUtils.getThroughput(overallDiff, timeDiff);
    double throughputMB = (long) MetricsUtils.getThroughputMB(overallDiff, timeDiff, msgSize);
    long spoutThroughput = (long) MetricsUtils.getThroughput(spoutDiff, timeDiff);
    double spoutThroughputMB = (long) MetricsUtils.getThroughputMB(spoutDiff, timeDiff, msgSize);
    metrics.put(TRANSFERRED, Long.toString(overallDiff));
    metrics.put(THROUGHPUT, Long.toString(throughput));
    metrics.put(THROUGHPUT_MB, String.format(THROUGHPUT_MB_FORMAT, throughputMB));
    metrics.put(SPOUT_TRANSFERRED, Long.toString(spoutDiff));
    metrics.put(SPOUT_ACKED, Long.toString(spoutAcked));
    metrics.put(SPOUT_THROUGHPUT, Long.toString(spoutThroughput));
    metrics.put(SPOUT_THROUGHPUT_MB,
            String.format(SPOUT_THROUGHPUT_MB_FORMAT, spoutThroughputMB));


    state.overallTransferred = overallTransferred;
    state.spoutTransferred = spoutTransferred;

  }


  boolean isSpout(ExecutorSpecificStats specs) {
    return specs != null && specs.is_set_spout();
  }

  boolean isDefaultStream(String stream) {
    return stream.equals(Utils.DEFAULT_STREAM_ID);
  }

  boolean isBatchStream(String stream) {
    return stream.equals("batch");
  }

  void addHeaders() {
    header.add(TIME);
    header.add(TOTAl_SLOTS);
    header.add(USED_SLOTS);
    header.add(WORKERS);
    header.add(TASKS);
    header.add(EXECUTORS);
    header.add(EXECUTORS_METRICS);
    header.add(TRANSFERRED);
    header.add(THROUGHPUT);
    header.add(THROUGHPUT_MB);
    header.add(SPOUT_EXECUTORS);
    header.add(SPOUT_TRANSFERRED);
    header.add(SPOUT_ACKED);
    header.add(SPOUT_THROUGHPUT);
    header.add(SPOUT_THROUGHPUT_MB);
  }

  void writeHeader(PrintWriter writer) {
    LOG.info("writing out headers into .csv file");
    writer.println(Utils.join(header, ","));
    writer.flush();
  }

  void writeLine(PrintWriter writer) {
    LOG.info("writing out one line data into .csv file");
    List<String> line = new LinkedList<String>();
    for (String h : header) {
      line.add(metrics.get(h));
    }
    writer.println(Utils.join(line, ","));
    writer.flush();
  }

  static class MetricsState {
    long overallTransferred = 0;
    long spoutTransferred = 0;
    long startTime = 0;
    long lastTime = 0;
  }
}
