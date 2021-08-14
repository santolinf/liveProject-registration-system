package com.manning.liveproject.camel.supplies;

import com.manning.liveproject.camel.supplies.bean.EnrichSupplyRecordAggregationStrategy;
import com.manning.liveproject.camel.supplies.bean.SupplyRecordToMapTransformer;
import com.manning.liveproject.camel.supplies.model.SupplyRecord;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.BindyType;

import static org.apache.camel.builder.endpoint.StaticEndpointBuilders.direct;

public class FileRdbmsRouteBuilder extends RouteBuilder {

    @Override
    public void configure() {

        from("file:{{com.manning.liveproject.camel.file-dir-options}}")
                .transacted()
                .unmarshal().bindy(BindyType.Csv, SupplyRecord.class)
                .split(body())
                    .bean(SupplyRecordToMapTransformer.class)   // transform record to a map for SQL processing
                    .to(direct("handle-supply-record"))
                .log("${body}")
        ;

        from("direct:handle-supply-record")
                .multicast(new EnrichSupplyRecordAggregationStrategy())
                    .to(direct("find-supplier-id"), direct("find-item-id"))
                    .end()
                .to(direct("update-records"))
        ;

        from(direct("find-supplier-id"))
                .to("sql:{{com.manning.liveproject.camel.sql-select-supplier}}")
        ;

        from(direct("find-item-id"))
                .to("sql:{{com.manning.liveproject.camel.sql-select-item}}")
        ;

        from(direct("update-records"))
                .to("sql:{{com.manning.liveproject.camel.sql-update-stock-levels}}")
                .to("sql:{{com.manning.liveproject.camel.sql-insert-shipment}}")
        ;
    }
}
