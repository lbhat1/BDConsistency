package bdconsistency;

import backtype.storm.Config;
import backtype.storm.LocalCluster;
import backtype.storm.StormSubmitter;
import backtype.storm.generated.AlreadyAliveException;
import backtype.storm.generated.DRPCExecutionException;
import backtype.storm.generated.InvalidTopologyException;
import backtype.storm.generated.StormTopology;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Values;
import backtype.storm.utils.DRPCClient;
import bdconsistency.query.AxFinderFilter;
import bdconsistency.query.PrinterBolt;
import com.google.common.collect.Lists;
import org.apache.thrift7.TException;
import storm.trident.Stream;
import storm.trident.TridentState;
import storm.trident.TridentTopology;
import storm.trident.operation.*;
import storm.trident.spout.ITridentSpout;
import storm.trident.spout.RichSpoutBatchExecutor;
import storm.trident.state.BaseQueryFunction;
import storm.trident.state.StateUpdater;
import storm.trident.testing.MemoryMapState;
import storm.trident.tuple.TridentTuple;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * User: lbhat@damsl
 * Date: 10/11/13
 * Time: 1:47 PM
 */
public class QCounter {

    public static class CountAggregator implements Aggregator<Double> {
        long count;
        @Override
        public Double init(Object batchId, TridentCollector collector) {
            return 0D;
        }

        @Override
        public void aggregate(Double val, TridentTuple tuple, TridentCollector collector) {
            count += 1;
            val = 1.0 * count;
            if (val % 1000 == 0)
                collector.emit(new Values(count));
        }

        @Override
        public void complete(Double val, TridentCollector collector) {}

        @Override
        public void prepare(Map conf, TridentOperationContext context) {}

        @Override
        public void cleanup() {}
    }

    public static StormTopology buildTopology(String fileName) {
        TridentTopology topology = new TridentTopology();
        final ITridentSpout asksSpout = new RichSpoutBatchExecutor(new FileStreamingSpout(fileName));
        Stream counts = topology
                .newStream("spout", asksSpout);

        counts.each(new Fields ("tradeString"), new AxFinderFilter.DummyFilter())
        .each(new Fields ("tradeString"), new AxFinderFilter.DummyFilter())
        .each(new Fields("tradeString"), new AxFinderFilter.DummyFilter())
                .aggregate(new CountAggregator(), new Fields("count"))
                .project(new Fields("count"))
                .each(new Fields("count"), new PrinterBolt());

        return topology.build();
    }

    public static void main(String[] args) throws AlreadyAliveException, InvalidTopologyException, InterruptedException, TException, DRPCExecutionException {
        Config conf = new Config();
        conf.setFallBackOnJavaSerialization(true);
        conf.setNumWorkers(20);
        conf.put(Config.DRPC_SERVERS, Lists.newArrayList("damsel", "qp4", "qp5", "qp6"));
        conf.setMaxSpoutPending(2);
        conf.put(Config.STORM_CLUSTER_MODE, "distributed");
        StormSubmitter.submitTopology("QCounter", conf, buildTopology(args[0]));

        try {
            Thread.sleep(120000);
        } catch (InterruptedException ignore) {
            ignore.printStackTrace();
        }

    }
}
