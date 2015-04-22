Java backend integration for Socket.IO library (http://socket.io/)

To use in a jetty webapp, grant access in your jetty xml file to the jetty server class org.eclipse.jetty.server.HttpConnection

<?xml version="1.0"?>
<!DOCTYPE Configure PUBLIC "-//Mort Bay Consulting//DTD Configure//EN" "http://jetty.mortbay.org/configure.dtd">
<Configure id="webappContext" class="org.eclipse.jetty.webapp.WebAppContext">

    <Set name="serverClasses">
        <Array type="java.lang.String">
            <Item>-org.eclipse.jetty.server.HttpConnection</Item>
            <Item>-org.eclipse.jetty.continuation.</Item>
            <Item>-org.eclipse.jetty.jndi.</Item>
            <Item>-org.eclipse.jetty.plus.jaas.</Item>
            <Item>-org.eclipse.jetty.websocket.</Item>
            <Item>-org.eclipse.jetty.servlet.DefaultServlet</Item>
            <Item>org.eclipse.jetty.</Item>
        </Array>
    </Set>

</Configure>

More info:

mvn eclipse:eclipse # for import the project in eclipse
mvn install -Dlicense.skip=true # to install the jars in your repo

Lauch from eclipse the Start* sample classes to test it.
