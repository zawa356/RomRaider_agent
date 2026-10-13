package com.romraider.logger.ecu.definition;
public class LoggerData {
 public String getId(){return "P8";}
 public String getName(){return "RPM";}
 public Converter getSelectedConvertor(){return new Converter();}
 public static class Converter { public String getUnits(){return "rpm";} }
}
