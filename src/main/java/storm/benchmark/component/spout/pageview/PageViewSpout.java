package storm.benchmark.component.spout.pageview;

import backtype.storm.spout.SpoutOutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseRichSpout;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Values;
import storm.benchmark.util.PageViewGenerator;

import java.util.Map;

public class PageViewSpout extends BaseRichSpout {

  public static final String FIELDS = "page_view";

  private SpoutOutputCollector collector;
  private final PageViewGenerator generator;
  private final boolean ackEnabled;
  private long count = 0;

  public PageViewSpout(boolean ackEnabled) {
    this.ackEnabled = ackEnabled;
    this.generator = new PageViewGenerator();
  }

  public PageViewSpout(boolean ackEnabled, PageViewGenerator generator) {
    this.ackEnabled = ackEnabled;
    this.generator = generator;
  }

  @Override
  public void declareOutputFields(OutputFieldsDeclarer declarer) {
    declarer.declare(new Fields(FIELDS));
  }

  @Override
  public void open(Map conf, TopologyContext context, SpoutOutputCollector collector) {
    this.collector = collector;
  }

  @Override
  public void nextTuple() {
    if (ackEnabled) {
      collector.emit(new Values(generator.getNextClickEvent()), count);
      count++;
    } else {
      collector.emit(new Values(generator.getNextClickEvent()));
    }
  }
}
