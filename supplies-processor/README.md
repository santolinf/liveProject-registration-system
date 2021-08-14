# Process Medical Supplies Shipment Files

Medical supplies data is to be sent to us as (CSV) files which are then placed in a folder on our diagnostic centre server.

Our task is to automate the process of ingesting the data and updating our item stock records in the database.

## Milestone 3 - The Deliverable

Running Camel as a standalone daemon thread, develop a solution that:

* reads supply data from files that are dropped in a specific folder on the server
* processes the data by updating specific records in the database

### 1. Create a Java class that implements a Camel RouteBuilder

```java
public class FileRdbmsRouteBuilder extends RouteBuilder {

    @Override
    public void configure() {
    }
}
```
This is the typical way to implement Camel routes irrespective of how we choose to
run Camel.

### 2. Read file using a File endpoint

```java
public class FileRdbmsRouteBuilder extends RouteBuilder {

    @Override
    public void configure() {

        from("file:{{com.manning.liveproject.camel.file-dir-options}}")
                .log("${body}");
    }
}
```
Here we use the `file` component and specify that we want to consume files (i.e., `from(...)`).

I have chosen to externalise the `file` endpoint URI path and options in a property. The main reason
for this is so that the daemon can run on several environments and that the 
directory names and file filters, for example, can be specified per environment instead of having
to modify the code everytime we want to deploy it somewhere.

> Note that when using Camel standalone we may need to programmatically override the default properties in the `application.properties` file
> with ones from the environment, if any.

The default file URI options.

```properties
com.manning.liveproject.camel.file-dir-options=data/in?noop=false&move=../processed&moveFailed=../error&fileName=${file:name.noext}.csv
```
Files are picked up and consumed from the `data/in` directory.


|URI option|description|
|---|---|
|*noop*|This can be removed altogether, however it is great during development and testing where we want the input file not to move or to be deleted and in this case set it to *true*|
|*move*|The directory location where the processed file will move to when the route was successful; this is set to `../processed` relative to the incoming file path directory|
|*moveFailed*|The directory location where the processed file will move to when the route failed; this is set to `../error` relative to the incoming file path directory|
|*fileName*|Specifies the pattern for the files to be consumed; we are only consuming CSV files|

### 3. Create a class that implements the CSV record

```java
@Getter
@ToString
@CsvRecord(separator = ",", crlf = "UNIX")
public class SupplyRecord {

    @DataField(pos = 1)
    private String shipmentCode;
    @DataField(pos = 2)
    private String supplierCode;
    @DataField(pos = 3)
    private String itemCode;
    @DataField(pos = 4)
    private Integer quantity;
    @DataField(pos = 5, pattern = "yyyy-MM-dd")
    private LocalDate shipmentDate;
}
```

The Camel *Bindy* component allows us to specify the layout and structure of the CVS file lines
and have the data mapped to a *POJO* for programmatic access ease. Each delimited field in a line is mapped
according to its position and converted to a specific type (e.g., `String`, `Integer`, `LocalDate`)  

This class is referred to from the route.

```java
        from("file:{{com.manning.liveproject.camel.file-dir-options}}")
                .unmarshal().bindy(BindyType.Csv, SupplyRecord.class)
                .split(body())
                    .log("${body}");
```

The contents of the file is read into memory by the `file` component.
We then `unmarshall` the contents into objects (`SupplyRecord`) using the *Bindy*
component.

The [Splitter EIP](https://camel.apache.org/components/latest/eips/split-eip.html) is
used to `split` the message body, which is a collection of `SupplyRecord`s, into individual 
objects so that we can process each record one at a time.

### 4. Update database, extracting data from CSV records

The `sql` component is used to work with the database and to specify the SQL queries to run.

There is support for using parameterised names for injecting dynamic values into the SQL queries.
This allows us to refer to fields or properties in the CSV record objects which are present
in the message body that is being processed.

Parameter names are specified using the following syntax: `:#<parameter name>`.

By default, the `sql` component will look into the message body and if it is a `Map`
object, it will try and get the value associated with the parameter name as the key.

> Although expression parameters and perhaps other means to inject values in to the SQL
> could be used, using parameter names that correspond to map keys are the simplest approach

First, we convert the CSV supply record object to a map object to support the `sql` component:

```java
        from("file:{{com.manning.liveproject.camel.file-dir-options}}")
                .unmarshal().bindy(BindyType.Csv, SupplyRecord.class)
                .split(body())
                    .bean(SupplyRecordToMapTransformer.class)
                    .to(direct("handle-supply-record"));
```

where the transforming logic is implemented by the following class:

```java
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
```

#### DataSource
The `sql` component needs a configured *DataSource* object. The DataSource provides the
details and configuration for obtaining a connection to the database.

Camel will use the `dataSource` bean provided to it if configured and registered in its own registry.

All we need to do is to specify the `dataSource` bean in the `application.properties` file and
Camel does the rest.

```properties
camel.beans.dataSource=#class:com.zaxxer.hikari.HikariDataSource
camel.beans.dataSource.jdbcUrl=jdbc:postgresql://localhost:5432/supplies_db
camel.beans.dataSource.username=postgres
camel.beans.dataSource.password=password
```

Here we are using the *HikariCP* JDBC connection pool implementation for the dataSource.

Whether the *PostgreSQL* runs as a locally installed process or on a local Docker container,
we refer to it using a `jdbcUrl`. If security is enabled, then the `username` and `password`
of the user connecting to the database must also be supplied.

#### SQL query and update statements
A decision was made to externalise the SQL statements as properties in the `application.properties` file.

```properties
com.manning.liveproject.camel.sql-select-supplier=select supplier_id as supplierid from suppliers where supplier_code=:#supplierCode
com.manning.liveproject.camel.sql-select-item=select item_id as itemid from items where item_code=:#itemCode
com.manning.liveproject.camel.sql-update-stock-levels=update stock_levels set quantity = quantity + :#quantity, last_updated = current_timestamp where item_id=:#itemid
com.manning.liveproject.camel.sql-insert-shipment=insert into shipments (shipment_code, supplier_id, item_id, quantity, shipment_date) values (:#shipmentCode, :#supplierid, :#itemid, :#quantity, :#shipmentDate)
```

Refer to the above properties from within the Camel `sql` endpoints using the `{{<property name>}}` syntax.

#### Retrieve information first, update later
The file CSV record is as follows:

|shipment code|supplier code|item code|quantity|shipment date|
|---|---|---|---|---|

In order to *update* the item stock levels, the `item_id` must be determined from the *item code*.

In order to *insert* the shipment record for the supplied item, both the `item_id` and `supplier_id` must be
determined from the *item code* and *supplier code*.

Therefore, two queries to the database are necessary to return the `item_id` and `supplier_id` that
match each incoming supply record.

Using [Scatter Gather EIP](https://camel.apache.org/components/latest/eips/scatter-gather.html)
allows the same supply record to be **sent to any number of recipient** endpoints and then for all the results to be
**aggregated** into one combined response message.

[Multicast EIP](https://camel.apache.org/components/latest/eips/multicast-eip.html) is used to send the
same message to a fixed number of recipient endpoints.

> Camel Multicast, Recipient List and Splitter EIPs have special support for using AggregationStrategy with access to
> the original input exchange; this is particularly useful when we would like to
> enrich the original message with the additional identifiers returned from the queries
> (as without it we would end up with the aggregate result from the queries alone)

```java
        from("direct:handle-supply-record")
                .multicast(new EnrichSupplyRecordAggregationStrategy())
                    .to(direct("find-supplier-id"), direct("find-item-id"))
                    .end()
                .to(direct("update-records"));
```

The recipient (direct) endpoints `find-supplier-id` and `find-item-id` will use the message body to provide 
the values to inject into the SQL SELECT statements and return the identifier values.

```java
        from(direct("find-supplier-id"))
                .to("sql:{{com.manning.liveproject.camel.sql-select-supplier}}");

        from(direct("find-item-id"))
                .to("sql:{{com.manning.liveproject.camel.sql-select-item}}");
```
Where the SQL for the above endpoints are found in the `application.properties` file.

```sql
select supplier_id as supplierid from suppliers where supplier_code=:#supplierCode
```
and

```sql
select item_id as itemid from items where item_code=:#itemCode
```

The `EnrichSupplyRecordAggregationStrategy` class implements the `AggregationStrategy` interface and
implements the logic for **merging** the results from the two endpoints into the original message.

```java
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
```

Now, the update statements can be executed as we will have all the necessary values, in the enriched
message body, to run the SQL statements.

```java
        from(direct("update-records"))
                .to("sql:{{com.manning.liveproject.camel.sql-update-stock-levels}}")
                .to("sql:{{com.manning.liveproject.camel.sql-insert-shipment}}");
```

Where the SQL for the above endpoints are found in the `application.properties` file.

```sql
update stock_levels set quantity = quantity + :#quantity, last_updated = current_timestamp where item_id=:#itemid
```

and

```sql
insert into shipments (shipment_code, supplier_id, item_id, quantity, shipment_date) values (:#shipmentCode, :#supplierid, :#itemid, :#quantity, :#shipmentDate)
```

### 5. Configure transactions
Without transaction support, the solution thus far won't guarantee data consistency if an error were to occur. 

For example, updates in the database are committed however soon after the route can encounter an error
that would cause the `file` component to *rollback* the file and move the file to the
`error` directory. This would leave the data in an inconsistent state because the records (either all or some) are now
up-to-date in the database but the file is marked as failed.

Also, the update stock levels operation should be undone if the insert shipment operation fails.

What we need is transactional support so that the route processing can either succeed or fail and the resources are 
managed as one unit (both file and database resources).

#### File Completion Strategy
The `file` component default `GenericFileOnCompletion` strategy will use a `GenericFileRenameProcessingStrategy`
to perform the work after the exchange has been processed, where the file is either moved to the `processed`
directory (commit) or moved to the `error` directory in the case of processing failure (rollback).

#### Spring Transaction Manager
Camel works with any of the Spring based Transaction Managers.

The [Transactional Client EIP](https://camel.apache.org/components/latest/eips/transactional-client.html)
provides us with a pattern we can follow and in particular it works with endpoints that support transactions 
so that they will participate in the current transaction context that they are called from.

In this case both the *update* and *insert* endpoints will be managed within the transaction context of the
route.

As we already have a *DataSource* to the PostgreSQL configured, all we need to do is to configure Spring's
`DataSourceTransactionManager` to do the rest.

```properties
camel.beans.txManager=#class:org.springframework.jdbc.datasource.DataSourceTransactionManager
camel.beans.txManager.dataSource=#dataSource
```

When we mark the route as **transacted**, Camel will look up the Spring transaction manager and use it by default.

```java
@Override
public void configure() {

        from("file:{{com.manning.liveproject.camel.file-dir-options}}")
                .transacted()
                .unmarshal().bindy(BindyType.Csv,SupplyRecord.class)
                .split(body())
                    .bean(SupplyRecordToMapTransformer.class)   // transform record to a map for SQL processing
                    .to(direct("handle-supply-record"));

        ...
}
```

### 6. Use the Main class to create a class that instantiates a Camel application

```java
public class SuppliesProcessorApplication {

    public static void main(String[] args) throws Exception {
        Main main = new Main();

        main.configure().addRoutesBuilder(FileRdbmsRouteBuilder.class);

        // now keep the application running until the JVM is terminated (ctrl + c or sigterm)
        main.run(args);
    }
}
```
