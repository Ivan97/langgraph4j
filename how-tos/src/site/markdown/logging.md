## Initialize Logger 


```java
var lm = java.util.logging.LogManager.getLogManager();
lm.checkAccess(); 
try( var file = new java.io.FileInputStream("./logging.properties")) {
    lm.readConfiguration( file );
    java.util.logging.Logger.getLogger("").addHandler(new java.util.logging.ConsoleHandler());
}
```

## Test Logger


```java

var log = org.slf4j.LoggerFactory.getLogger(org.bsc.langgraph4j.CompiledGraph.class);


log.trace( "this is a trace message {}", "TRACE2");
log.info( "this is a info message {}", "INFO1");

```

    this is a trace message TRACE2 
    this is a trace message TRACE2 
    this is a info message INFO1 
    this is a info message INFO1 

