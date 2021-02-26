package a2t.tomcat.deploy;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import javax.servlet.ServletContextListener;
import javax.servlet.ServletContextEvent;

public class A2TDeployHook implements ServletContextListener {

    private static final Log logger = LogFactory.getLog(A2TDeployHook.class);

    @Override
    public void contextDestroyed(ServletContextEvent e) {
        logger.warn("AppContext Delete: " + e.getServletContext().getContextPath());
    }

    @Override
    public void contextInitialized(ServletContextEvent e) {
        logger.warn("AppContext Create: " + e.getServletContext().getContextPath());
    }

}
