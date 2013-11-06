package bdconsistency.bolt.trident.query;

import backtype.storm.tuple.Values;
import bdconsistency.state.tpch.ITpchTable;
import bdconsistency.state.tpch.TpchState;
import storm.trident.operation.BaseAggregator;
import storm.trident.operation.TridentCollector;
import storm.trident.state.BaseQueryFunction;
import storm.trident.tuple.TridentTuple;

import java.io.Serializable;
import java.util.*;


public class TpchQuery {

    public static class Query3Aggregator extends BaseAggregator<Query3Aggregator.Query3Result> {
        @Override
        public Query3Result init (final Object batchId, final TridentCollector collector) {
            return null;
        }

        @Override
        public void aggregate (Query3Result query3Result, final TridentTuple tuple, final TridentCollector collector) {
            if (query3Result == null) {
                query3Result = new Query3Result();
                query3Result.orderDate = tuple.getIntegerByField("orderdate");
                query3Result.orderKey = tuple.getIntegerByField("orderkey");
                query3Result.shipPriority = tuple.getIntegerByField("shippriority");
            }
            query3Result.query3 += tuple.getDoubleByField("extendedprice") * (1 - tuple.getDoubleByField("discount"));
        }

        @Override
        public void complete (final Query3Result val, final TridentCollector collector) {
            collector.emit(new Values(val.orderKey, val.orderDate, val.shipPriority, val.query3));
        }

        class Query3Result {
            int orderKey, orderDate, shipPriority;
            double query3;
        }
    }

    /*
     SELECT o.orderkey, o.orderdate, o.shippriority, SUM(extendedprice * (1 - discount)) AS query3
     FROM Customer c, Orders o, Lineitem l
     WHERE c.mktsegment = 'BUILDING'
     AND o.custkey = c.custkey
     AND l.orderkey = o.orderkey
                                     AND o.orderdate < DATE('1995-03-15')
                                     AND l.shipdate > DATE('1995-03-15')
     GROUP BY o.orderkey, o.orderdate, o.shippriority;
     **/
    public static class Query3IntermediateResult implements Serializable {
        Query3IntermediateResult (int orderKey, int orderDate, int shipPriority, double extendedPrice, double discount) {
            this.orderDate = orderDate;
            this.orderKey = orderKey;
            this.shipPriority = shipPriority;
            this.extendedPrice = extendedPrice;
            this.discount = discount;
        }

        int orderKey, orderDate, shipPriority;
        double extendedPrice, discount;
    }

    public static class Query3 extends BaseQueryFunction<TpchState, List<Query3IntermediateResult>> {
        int maxOrderDate, maxShipDate, marketSegment;

        public Query3 () {}

        public Query3 (int marketSegment, int maxOrderDate, int maxShipDate) {
            this.marketSegment = marketSegment;
            this.maxOrderDate = maxOrderDate;
            this.maxShipDate = maxShipDate;
        }

        @Override
        public List<List<Query3IntermediateResult>> batchRetrieve (final TpchState state, final List<TridentTuple> args) {

            // I know this is horrible, I don't know a better way to do this
            String[] predicates = args.get(0).getStringByField("args").split(",");
            try {
                marketSegment = Integer.valueOf(predicates[0]);
                maxOrderDate = Integer.valueOf(predicates[1]);
                maxShipDate = Integer.valueOf(predicates[2]);
            } catch ( NumberFormatException ignore ) {}

            List<List<Query3IntermediateResult>> returnList = new LinkedList<List<Query3IntermediateResult>>();
            List<Query3IntermediateResult> results = new ArrayList<Query3IntermediateResult>();
            ITpchTable orders = state.getTable("orders");
            ITpchTable customer = state.getTable("customer");
            ITpchTable lineItem = state.getTable("lineitem");

            filterCustomers(customer);
            filterOrders(orders);
            filterLineItems(lineItem);

            for (Object l : lineItem.getRows()) {
                TpchState.LineItem.LineItemBean lBean = (TpchState.LineItem.LineItemBean) l;
                for (Object o : orders.getRows()) {
                    TpchState.Orders.OrderBean orderBean = (TpchState.Orders.OrderBean) o;
                    for (Object c : customer.getRows()) {
                        TpchState.Customer.CustBean cBean = (TpchState.Customer.CustBean) c;
                        if (orderBean.getCustomerKey() == cBean.getCustomerKey() && lBean.getOrderKey() == orderBean.getOrderKey()) {
                            results.add(new Query3IntermediateResult(orderBean.getOrderKey(), orderBean.getOrderDate(),
                                                                     orderBean.getShipPriority(), lBean.getExtendedPrice(), lBean.getDiscount()));
                        }
                    }
                }
            }
            returnList.add(results);
            for (int i = 1; i < args.size(); i++) returnList.add(null);
            return returnList;
        }

        @Override
        public void execute (final TridentTuple tuple, final List<Query3IntermediateResult> results, final TridentCollector collector) {
            if (results != null)
                for (Query3IntermediateResult result : results)
                    collector.emit(new Values(result.orderKey, result.orderDate, result.shipPriority, result.extendedPrice));
        }

        private void filterCustomers (final ITpchTable customer) {
            final Set rows = customer.getRows();
            Iterator<TpchState.Customer.CustBean> iterator = rows.iterator();
            while (iterator.hasNext()) {
                final TpchState.Customer.CustBean bean = iterator.next();
                if (bean.getMarketSegment() != marketSegment)
                    iterator.remove();
            }
        }

        private void filterLineItems (final ITpchTable lineItem) {
            final Set rows = lineItem.getRows();
            Iterator<TpchState.LineItem.LineItemBean> iterator = rows.iterator();
            while (iterator.hasNext()) {
                final TpchState.LineItem.LineItemBean bean = iterator.next();
                if (bean.getShipDate() > maxShipDate)
                    iterator.remove();
            }
        }

        private void filterOrders (final ITpchTable orders) {
            final Set rows = orders.getRows();
            Iterator<TpchState.Orders.OrderBean> iterator = rows.iterator();
            while (iterator.hasNext()) {
                final TpchState.Orders.OrderBean bean = iterator.next();
                if (bean.getOrderDate() > maxOrderDate)
                    iterator.remove();
            }
        }
    }
}