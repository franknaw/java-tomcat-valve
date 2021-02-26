## A2T Access Log Valve
The A2T access log valve is a component that extends the Tomcat Valve API and is inserted into the request processing pipeline for the A2T Catalina container.  The component writes HTTP access logs to the A2T MongoDB instance, this then allows access to real-time user request information. (user metrics).  The following outlines the procedures for configuring and deploying.

1. Add the following to the Tomcat server.xml file (near the bottom of the file)
> 
       <Valve className="a2t.tomcat.valve.mongo.A2TAccessLogValve"
                host="mongohost"
                port="27017"
                dbName="production"
                collName="tomcat-access-logs"
                rotatable="false"
                user="someuser"
                pass="somepass"
                pattern="%a %B %m %s %S %t %U %T %P %{Referer}i %{User-Agent}i %{user}c"
                excludes=".js,.css,jpg,.jpeg,.gif,.png,.bmp,.gif,.ico,.html,.htm,.woff2,.woff,/notification,/bower_components,/userSession,/spring_security_login,/accessLogs"/>
> 


2. Enable (default), logs written to Tomcat logging directory. (note: updated log pattern)
> 
       <Valve className="org.apache.catalina.valves.AccessLogValve" directory="logs"
               prefix="localhost_access_log" suffix=".txt"
               pattern="%a %B %m %s %S %t %U %T %P %{Referer}i %{User-Agent}i %{user}c"/>
> 

3. Copy the "a2t-tomcat-valve.jar" to the tomcat/lib directory.
4. Restart Tomcat and watch logs to ensure the new component loads properly.
5. If all is good then access logs should be written to the mongoDB collection (specified above - tomcat-access-logs).


***
Reference:  
https://tomcat.apache.org/tomcat-8.0-doc/config/valve.html#Access_Log_Valve  
https://tomcat.apache.org/tomcat-8.0-doc/api/org/apache/catalina/valves/ValveBase.html  

