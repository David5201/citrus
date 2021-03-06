package com.consol.citrus.validation.interceptor;

import com.consol.citrus.variable.dictionary.DataDictionary;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * Registry factory bean automatically adds all validation matcher libraries that live in the Spring bean application context.
 * The default validation matcher library is also added via Spring bean reference. This is why this registry explicitly doe not use default registry
 * in order to not duplicate the default validation matcher library.
 *
 * @author Christoph Deppisch
 */
public class MessageConstructionInterceptorsFactory implements FactoryBean<MessageConstructionInterceptors>, ApplicationContextAware {

    private ApplicationContext applicationContext;

    private final MessageConstructionInterceptors registry;

    /**
     * Default constructor.
     */
    public MessageConstructionInterceptorsFactory() {
        this(new MessageConstructionInterceptors());
    }

    /**
     * Constructor initializes with given registry.
     * @param registry
     */
    public MessageConstructionInterceptorsFactory(MessageConstructionInterceptors registry) {
        this.registry = registry;
    }

    @Override
    public MessageConstructionInterceptors getObject() throws Exception {
        if (applicationContext != null) {
            applicationContext.getBeansOfType(MessageConstructionInterceptor.class)
                    .entrySet()
                    .stream()
                    .filter(entry -> !(entry.getValue() instanceof DataDictionary) || ((DataDictionary<?>) entry.getValue()).isGlobalScope())
                    .forEach(entry -> registry.addMessageConstructionInterceptor(entry.getValue()));
        }

        return registry;
    }

    @Override
    public Class<?> getObjectType() {
        return MessageConstructionInterceptors.class;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
