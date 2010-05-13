package org.jboss.resteasy.plugins.server.servlet;

import org.jboss.resteasy.spi.ResteasyDeployment;

import javax.servlet.FilterConfig;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class FilterBootstrap extends ListenerBootstrap
{
   private FilterConfig config;

   public FilterBootstrap(FilterConfig config)
   {
      super(config.getServletContext());
      this.config = config;
   }

   @Override
   public ResteasyDeployment createDeployment()
   {
      ResteasyDeployment deployment = super.createDeployment();
      deployment.getDefaultContextObjects().put(FilterConfig.class, config);
      return deployment;
   }

   public String getParameter(String name)
   {
      String val = config.getInitParameter(name);
      if (val == null) val = super.getParameter(name);
      return val;
   }
}