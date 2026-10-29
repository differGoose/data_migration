package com.datamigration.support;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@org.springframework.context.annotation.Import(HostDataSources.class)
public class ResilienceHost {}
