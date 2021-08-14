package com.manning.liveproject.camel.supplies;

import org.apache.camel.main.Main;

public class SuppliesProcessorApplication {

    public static void main(String[] args) throws Exception {
        Main main = new Main();

        main.configure().addRoutesBuilder(FileRdbmsRouteBuilder.class);

        // now keep the application running until the JVM is terminated (ctrl + c or sigterm)
        main.run(args);
    }
}
