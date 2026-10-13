package com.romraider.logger.ecu.comms.query;
import com.romraider.logger.ecu.definition.LoggerData;
import java.util.List;
public class Response {
 public List<LoggerData> getData(){return java.util.Collections.emptyList();}
 public long getTimestamp(){return 0L;}
 public double getDataValue(LoggerData d){return 0.0;}
}
