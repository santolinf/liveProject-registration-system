package com.manning.liveproject.camel.supplies.bean;

import com.google.common.collect.ImmutableMap;
import com.manning.liveproject.camel.supplies.model.SupplyRecord;
import org.apache.camel.Body;
import org.apache.camel.Handler;

import java.util.Map;

public class SupplyRecordToMapTransformer {

    @Handler
    public Map<String, Object> toMap(@Body SupplyRecord record) {
        return ImmutableMap.of(
                "shipmentCode", record.getShipmentCode(),
                "supplierCode", record.getSupplierCode(),
                "itemCode", record.getItemCode(),
                "quantity", record.getQuantity(),
                "shipmentDate", record.getShipmentDate()
        );
    }
}
