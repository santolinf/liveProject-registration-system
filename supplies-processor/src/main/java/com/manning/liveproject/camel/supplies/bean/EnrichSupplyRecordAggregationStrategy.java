package com.manning.liveproject.camel.supplies.bean;

import com.google.common.collect.Maps;
import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;

import java.util.List;
import java.util.Map;

public class EnrichSupplyRecordAggregationStrategy implements AggregationStrategy {

    @SuppressWarnings("unchecked")
    @Override
    public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
        // first time we may have only new exchange
        if (oldExchange == null) {
            return newExchange;
        }

        Map<String, Object> oldBody = oldExchange.getIn().getBody(Map.class);
        List<Map<String, Object>> newBody = newExchange.getIn().getBody(List.class);
        Map<String, Object> merged = Maps.newHashMap(oldBody);
        newBody.forEach(merged::putAll);
        oldExchange.getIn().setBody(merged);
        return oldExchange;
    }

    /**
     * Multicast, Recipient List and Splitter EIPs have special support for using AggregationStrategy with access to
     * the original input exchange, which is this method.
     */
    @Override
    public Exchange aggregate(Exchange oldExchange, Exchange newExchange, Exchange inputExchange) {
        if (oldExchange == null) {
            return aggregate(inputExchange, newExchange);
        } else {
            return aggregate(oldExchange, newExchange);
        }
    }
}
