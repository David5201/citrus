/*
 * Copyright 2006-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.consol.citrus.validation.xml;

import com.consol.citrus.CitrusConstants;
import com.consol.citrus.context.TestContext;
import com.consol.citrus.exceptions.CitrusRuntimeException;
import com.consol.citrus.exceptions.ValidationException;
import com.consol.citrus.message.*;
import com.consol.citrus.util.XMLUtils;
import com.consol.citrus.validation.ControlMessageValidator;
import com.consol.citrus.validation.ValidationUtils;
import com.consol.citrus.validation.context.ValidationContext;
import com.consol.citrus.validation.matcher.ValidationMatcherUtils;
import com.consol.citrus.xml.XsdSchemaRepository;
import com.consol.citrus.xml.namespace.NamespaceContextBuilder;
import com.consol.citrus.xml.schema.MultiResourceXsdSchema;
import com.consol.citrus.xml.schema.WsdlXsdSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.util.*;
import org.springframework.xml.validation.XmlValidator;
import org.springframework.xml.validation.XmlValidatorFactory;
import org.springframework.xml.xsd.XsdSchema;
import org.w3c.dom.*;
import org.w3c.dom.ls.LSException;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;

/**
 * Default message validator implementation. Working on XML messages
 * providing message payload, header and namespace validation.
 *
 * @author Christoph Deppisch
 * @since 2007
 */
public class DomXmlMessageValidator extends ControlMessageValidator<XmlMessageValidationContext> implements ApplicationContextAware {
    /** Logger */
    private static Logger log = LoggerFactory.getLogger(DomXmlMessageValidator.class);
    
    @Autowired(required = false)
    private List<XsdSchemaRepository> schemaRepositories = new ArrayList<XsdSchemaRepository>();

    @Autowired(required = false)
    private NamespaceContextBuilder namespaceContextBuilder = new NamespaceContextBuilder();

    /** Root application context this validator is defined in */
    private ApplicationContext applicationContext;

    /** Transformer factory */
    private TransformerFactory transformerFactory = TransformerFactory.newInstance();

    @Override
    public void validateMessagePayload(Message receivedMessage, Message controlMessage,
                                       XmlMessageValidationContext validationContext, TestContext context) throws ValidationException {
        log.info("Start XML message validation");

        try {
            if (validationContext.isSchemaValidationEnabled()) {
                validateXMLSchema(receivedMessage, validationContext);
                validateDTD(validationContext.getDTDResource(), receivedMessage);
            }

            validateNamespaces(validationContext.getControlNamespaces(), receivedMessage);
            validateMessageContent(receivedMessage, controlMessage, validationContext, context);

            if (controlMessage != null) {
                Assert.isTrue(controlMessage.getHeaderData().size() <= receivedMessage.getHeaderData().size(),
                        "Failed to validate header data XML fragments - found " +
                                receivedMessage.getHeaderData().size() + " header fragments, expected " + controlMessage.getHeaderData().size());

                for (int i = 0; i < controlMessage.getHeaderData().size(); i++) {
                    validateXmlHeaderFragment(receivedMessage.getHeaderData().get(i),
                            controlMessage.getHeaderData().get(i), validationContext, context);
                }

            }

            log.info("XML message validation successful: All values OK");
        } catch (ClassCastException e) {
            throw new CitrusRuntimeException(e);
        } catch (DOMException e) {
            throw new CitrusRuntimeException(e);
        } catch (LSException e) {
            throw new CitrusRuntimeException(e);
        } catch (IllegalArgumentException e) {
            log.error("Failed to validate:\n" + XMLUtils.prettyPrint(receivedMessage.getPayload().toString()));
            throw new ValidationException("Validation failed:", e);
        } catch (ValidationException ex) {
            log.error("Failed to validate:\n" + XMLUtils.prettyPrint(receivedMessage.getPayload().toString()));
            throw ex;
        }
    }

    /**
     * Validate message with a DTD.
     *
     * @param dtdResource
     * @param receivedMessage
     */
    protected void validateDTD(Resource dtdResource, Message receivedMessage) {
        //TODO implement this
    }

    /**
     * Validate message with a XML schema.
     *
     * @param receivedMessage
     * @param validationContext
     */
    protected void validateXMLSchema(Message receivedMessage, XmlMessageValidationContext validationContext) {
        if (receivedMessage.getPayload() == null || !StringUtils.hasText(receivedMessage.getPayload().toString())) {
            return;
        }

        try {
            Document doc = XMLUtils.parseMessagePayload(receivedMessage.getPayload().toString());

            if (!StringUtils.hasText(doc.getFirstChild().getNamespaceURI())) {
                return;
            }

            log.info("Starting XML schema validation ...");

            XmlValidator validator = null;
            XsdSchemaRepository schemaRepository = null;
            if (validationContext.getSchema() != null) {
                validator = applicationContext.getBean(validationContext.getSchema(), XsdSchema.class).createValidator();
            } else if (validationContext.getSchemaRepository() != null) {
                schemaRepository = applicationContext.getBean(validationContext.getSchemaRepository(), XsdSchemaRepository.class);
            } else if (schemaRepositories.size() == 1) {
                schemaRepository = schemaRepositories.get(0);
            } else if (schemaRepositories.size() > 0) {
                for (XsdSchemaRepository repository : schemaRepositories) {
                    if (repository.canValidate(doc)) {
                        schemaRepository = repository;
                    }
                }
                
                if (schemaRepository == null) {
                    throw new CitrusRuntimeException(String.format("Failed to find proper schema repository in Spring bean context for validating element '%s(%s)'",
                            doc.getFirstChild().getLocalName(), doc.getFirstChild().getNamespaceURI()));
                }
            } else {
                log.warn("Neither schema instance nor schema repository defined - skipping XML schema validation");
                return;
            }
            
            if (schemaRepository != null) {
                if (!schemaRepository.canValidate(doc)) {
                    throw new CitrusRuntimeException(String.format("Unable to find proper XML schema definition for element '%s(%s)' in schema repository '%s'",
                            doc.getFirstChild().getLocalName(),
                            doc.getFirstChild().getNamespaceURI(),
                            schemaRepository.getName()));
                }

                List<Resource> schemas = new ArrayList<Resource>();

                for (XsdSchema xsdSchema : schemaRepository.getSchemas()) {
                    if (xsdSchema instanceof MultiResourceXsdSchema) {
                        for (Resource resource : ((MultiResourceXsdSchema) xsdSchema).getSchemas()) {
                            schemas.add(resource);
                        }                            
                    } else if (xsdSchema instanceof WsdlXsdSchema) {
                        for (Resource resource : ((WsdlXsdSchema) xsdSchema).getSchemas()) {
                            schemas.add(resource);
                        }
                    } else {
                        synchronized (transformerFactory) {
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            try {
                                transformerFactory.newTransformer().transform(xsdSchema.getSource(), new StreamResult(bos));
                            } catch (TransformerException e) {
                                throw new CitrusRuntimeException("Failed to read schema " + xsdSchema.getTargetNamespace(), e);
                            }
                            schemas.add(new ByteArrayResource(bos.toByteArray()));
                        }
                    }
                }
                
                validator = XmlValidatorFactory.createValidator(schemas.toArray(new Resource[schemas.size()]), WsdlXsdSchema.W3C_XML_SCHEMA_NS_URI);
            }
            
            SAXParseException[] results = validator.validate(new DOMSource(doc));
            if (results.length == 0) {
                log.info("Schema of received XML validated OK");
            } else {
                log.error("Schema validation failed for message:\n" +
                        XMLUtils.prettyPrint(receivedMessage.getPayload().toString()));
                
                // Report all parsing errors
                log.debug("Found " + results.length + " schema validation errors");
                StringBuilder errors = new StringBuilder();
                for(SAXParseException e : results) {
                    errors.append(e.toString());
                    errors.append("\n");
                }
                log.debug(errors.toString());

                throw new ValidationException("Schema validation failed:", results[0]);
            }
        } catch (IOException e) {
            throw new CitrusRuntimeException(e);
        } catch (SAXException e) {
            throw new CitrusRuntimeException(e);
        }
    }

    /**
     * Validate namespaces in message. The method compares namespace declarations in the root
     * element of the received message to expected namespaces. Prefixes are important too, so
     * differing namespace prefixes will fail the validation.
     *
     * @param expectedNamespaces
     * @param receivedMessage
     */
    protected void validateNamespaces(Map<String, String> expectedNamespaces, Message receivedMessage) {
        if (CollectionUtils.isEmpty(expectedNamespaces)) { return; }

        if (receivedMessage.getPayload() == null || !StringUtils.hasText(receivedMessage.getPayload().toString())) {
            throw new ValidationException("Unable to validate message namespaces - receive message payload was empty");
        }

        log.info("Start XML namespace validation");

        Document received = XMLUtils.parseMessagePayload(receivedMessage.getPayload().toString());

        Map<String, String> foundNamespaces = XMLUtils.lookupNamespaces(receivedMessage.getPayload().toString());

        if (foundNamespaces.size() != expectedNamespaces.size()) {
            throw new ValidationException("Number of namespace declarations not equal for node " +
                    XMLUtils.getNodesPathName(received.getFirstChild()) + " found " +
                    foundNamespaces.size() + " expected " + expectedNamespaces.size());
        }

        for (Entry<String, String> entry : expectedNamespaces.entrySet()) {
            String namespace = entry.getKey();
            String url = entry.getValue();

            if (foundNamespaces.containsKey(namespace)) {
                if (!foundNamespaces.get(namespace).equals(url)) {
                    throw new ValidationException("Namespace '" + namespace +
                            "' values not equal: found '" + foundNamespaces.get(namespace) +
                            "' expected '" + url + "' in reference node " +
                            XMLUtils.getNodesPathName(received.getFirstChild()));
                } else {
                    log.info("Validating namespace " + namespace + " value as expected " + url + " - value OK");
                }
            } else {
                throw new ValidationException("Missing namespace " + namespace + "(" + url + ") in node " +
                        XMLUtils.getNodesPathName(received.getFirstChild()));
            }
        }

        log.info("XML namespace validation finished successfully: All values OK");
    }

    private void doElementNameValidation(Node received, Node source) {
        //validate element name
        if (log.isDebugEnabled()) {
            log.debug("Validating element: " + received.getLocalName() + " (" + received.getNamespaceURI() + ")");
        }

        Assert.isTrue(received.getLocalName().equals(source.getLocalName()),
                ValidationUtils.buildValueMismatchErrorMessage("Element names not equal", source.getLocalName(), received.getLocalName()));
    }

    private void doElementNamespaceValidation(Node received, Node source) {
        //validate element namespace
        if (log.isDebugEnabled()) {
            log.debug("Validating namespace for element: " + received.getLocalName());
        }

        if (received.getNamespaceURI() != null) {
            Assert.isTrue(source.getNamespaceURI() != null,
                    ValidationUtils.buildValueMismatchErrorMessage("Element namespace not equal for element '" +
                        received.getLocalName() + "'", null, received.getNamespaceURI()));

            Assert.isTrue(received.getNamespaceURI().equals(source.getNamespaceURI()),
                    ValidationUtils.buildValueMismatchErrorMessage("Element namespace not equal for element '" +
                    received.getLocalName() + "'", source.getNamespaceURI(), received.getNamespaceURI()));
        } else {
            Assert.isTrue(source.getNamespaceURI() == null,
                    ValidationUtils.buildValueMismatchErrorMessage("Element namespace not equal for element '" +
                    received.getLocalName() + "'", source.getNamespaceURI(), null));
        }
    }

    /**
     * Validate message payloads by comparing to a control message.
     *
     * @param receivedMessage
     * @param validationContext
     * @param context
     */
    protected void validateMessageContent(Message receivedMessage, Message controlMessage, XmlMessageValidationContext validationContext,
            TestContext context) {
        if (controlMessage == null || controlMessage.getPayload() == null) {
            log.info("Skip message payload validation as no control message was defined");
            return;
        }

        if (!(controlMessage.getPayload() instanceof String)) {
            throw new IllegalArgumentException(
                    "DomXmlMessageValidator does only support message payload of type String, " +
                    "but was " + controlMessage.getPayload().getClass());
        }

        String controlMessagePayload = controlMessage.getPayload().toString();

        if (receivedMessage.getPayload() == null || !StringUtils.hasText(receivedMessage.getPayload().toString())) {
            Assert.isTrue(!StringUtils.hasText(controlMessagePayload),
                    "Unable to validate message payload - received message payload was empty, control message payload is not");
            return;
        } else if (!StringUtils.hasText(controlMessagePayload)) {
            return;
        }

        log.info("Start XML tree validation ...");

        Document received = XMLUtils.parseMessagePayload(receivedMessage.getPayload().toString());
        Document source = XMLUtils.parseMessagePayload(controlMessagePayload);

        XMLUtils.stripWhitespaceNodes(received);
        XMLUtils.stripWhitespaceNodes(source);

        if (log.isDebugEnabled()) {
            log.debug("Received message:\n" + XMLUtils.serialize(received));
            log.debug("Control message:\n" + XMLUtils.serialize(source));
        }

        validateXmlTree(received, source, validationContext, namespaceContextBuilder.buildContext(
                receivedMessage, validationContext.getNamespaces()), context);
    }
    
    /**
     * Validates XML header fragment data.
     * @param receivedHeaderData
     * @param controlHeaderData
     * @param validationContext
     * @param context
     */
    private void validateXmlHeaderFragment(String receivedHeaderData, String controlHeaderData,
            XmlMessageValidationContext validationContext, TestContext context) {
        log.info("Start XML header data validation ...");

        Document received = XMLUtils.parseMessagePayload(receivedHeaderData);
        Document source = XMLUtils.parseMessagePayload(controlHeaderData);

        XMLUtils.stripWhitespaceNodes(received);
        XMLUtils.stripWhitespaceNodes(source);

        if (log.isDebugEnabled()) {
            log.debug("Received header data:\n" + XMLUtils.serialize(received));
            log.debug("Control header data:\n" + XMLUtils.serialize(source));
        }

        validateXmlTree(received, source, validationContext, 
                namespaceContextBuilder.buildContext(new DefaultMessage(receivedHeaderData), validationContext.getNamespaces()),
                context);
        
    }

    /**
     * Walk the XML tree and validate all nodes.
     *
     * @param received
     * @param source
     * @param validationContext
     */
    private void validateXmlTree(Node received, Node source, 
            XmlMessageValidationContext validationContext, NamespaceContext namespaceContext, TestContext context) {
        switch(received.getNodeType()) {
            case Node.DOCUMENT_TYPE_NODE:
                doDocumentTypeDefinition(received, source, validationContext, namespaceContext, context);
                break;
            case Node.DOCUMENT_NODE:
                validateXmlTree(received.getFirstChild(), source.getFirstChild(),
                        validationContext, namespaceContext, context);
                break;
            case Node.ELEMENT_NODE:
                doElement(received, source, validationContext, namespaceContext, context);
                break;
            case Node.TEXT_NODE: case Node.CDATA_SECTION_NODE:
                doText(received, source);
                break;
            case Node.ATTRIBUTE_NODE:
                throw new IllegalStateException();
            case Node.COMMENT_NODE:
                doComment(received);
                break;
            case Node.PROCESSING_INSTRUCTION_NODE:
                doPI(received);
                break;
        }
    }

    /**
     * Handle document type definition with validation of publicId and systemId.
     * @param received
     * @param source
     * @param validationContext
     * @param namespaceContext
     */
    private void doDocumentTypeDefinition(Node received, Node source,
            XmlMessageValidationContext validationContext,
            NamespaceContext namespaceContext, TestContext context) {

        Assert.isTrue(source instanceof DocumentType, "Missing document type definition in expected xml fragment");

        DocumentType receivedDTD = (DocumentType) received;
        DocumentType sourceDTD = (DocumentType) source;

        if (log.isDebugEnabled()) {
            log.debug("Validating document type definition: " +
                    receivedDTD.getPublicId() + " (" + receivedDTD.getSystemId() + ")");
        }

        if (!StringUtils.hasText(sourceDTD.getPublicId())) {
            Assert.isNull(receivedDTD.getPublicId(),
                    ValidationUtils.buildValueMismatchErrorMessage("Document type public id not equal",
                    sourceDTD.getPublicId(), receivedDTD.getPublicId()));
        } else if (sourceDTD.getPublicId().trim().equals(CitrusConstants.IGNORE_PLACEHOLDER)) {
            if (log.isDebugEnabled()) {
                log.debug("Document type public id: '" + receivedDTD.getPublicId() +
                        "' is ignored by placeholder '" + CitrusConstants.IGNORE_PLACEHOLDER + "'");
            }
        } else {
            Assert.isTrue(StringUtils.hasText(receivedDTD.getPublicId()) &&
                    receivedDTD.getPublicId().equals(sourceDTD.getPublicId()),
                    ValidationUtils.buildValueMismatchErrorMessage("Document type public id not equal",
                    sourceDTD.getPublicId(), receivedDTD.getPublicId()));
        }

        if (!StringUtils.hasText(sourceDTD.getSystemId())) {
            Assert.isNull(receivedDTD.getSystemId(),
                    ValidationUtils.buildValueMismatchErrorMessage("Document type system id not equal",
                    sourceDTD.getSystemId(), receivedDTD.getSystemId()));
        } else if (sourceDTD.getSystemId().trim().equals(CitrusConstants.IGNORE_PLACEHOLDER)) {
            if (log.isDebugEnabled()) {
                log.debug("Document type system id: '" + receivedDTD.getSystemId() +
                        "' is ignored by placeholder '" + CitrusConstants.IGNORE_PLACEHOLDER + "'");
            }
        } else {
            Assert.isTrue(StringUtils.hasText(receivedDTD.getSystemId()) &&
                    receivedDTD.getSystemId().equals(sourceDTD.getSystemId()),
                    ValidationUtils.buildValueMismatchErrorMessage("Document type system id not equal",
                    sourceDTD.getSystemId(), receivedDTD.getSystemId()));
        }

        validateXmlTree(received.getNextSibling(),
                source.getNextSibling(), validationContext, namespaceContext, context);
    }

    /**
     * Handle element node.
     *
     * @param received
     * @param source
     * @param validationContext
     */
    private void doElement(Node received, Node source,
            XmlMessageValidationContext validationContext, NamespaceContext namespaceContext, TestContext context) {

        doElementNameValidation(received, source);

        doElementNamespaceValidation(received, source);

        //check if element is ignored either by xpath or by ignore placeholder in source message
        if(XmlValidationUtils.isElementIgnored(source, received, validationContext.getIgnoreExpressions(), namespaceContext)) {
            return;
        }

        //work on attributes
        if (log.isDebugEnabled()) {
            log.debug("Validating attributes for element: " + received.getLocalName());
        }
        NamedNodeMap receivedAttr = received.getAttributes();
        NamedNodeMap sourceAttr = source.getAttributes();

        Assert.isTrue(countAttributes(receivedAttr) == countAttributes(sourceAttr),
                ValidationUtils.buildValueMismatchErrorMessage("Number of attributes not equal for element '"
                        + received.getLocalName() + "'", countAttributes(sourceAttr), countAttributes(receivedAttr)));

        for (int i = 0; i < receivedAttr.getLength(); i++) {
            doAttribute(received, receivedAttr.item(i), source, validationContext, namespaceContext, context);
        }

        //check if validation matcher on element is specified
        if (isValidationMatcherExpression(source)) {
            ValidationMatcherUtils.resolveValidationMatcher(source.getNodeName(),
                    received.getFirstChild().getNodeValue().trim(),
                    source.getFirstChild().getNodeValue().trim(),
                    context);
            return;
        }

        //work on child nodes
        NodeList receivedChilds = received.getChildNodes();
        NodeList sourceChilds = source.getChildNodes();

        Assert.isTrue(receivedChilds.getLength() == sourceChilds.getLength(),
                ValidationUtils.buildValueMismatchErrorMessage("Number of child elements not equal for element '"
                    + received.getLocalName() + "'", sourceChilds.getLength(), receivedChilds.getLength()));

        for(int i = 0; i<receivedChilds.getLength(); i++) {
            this.validateXmlTree(receivedChilds.item(i), sourceChilds.item(i),
                    validationContext, namespaceContext, context);
        }

        if (log.isDebugEnabled()) {
            log.debug("Validation successful for element: " + received.getLocalName() +
                    " (" + received.getNamespaceURI() + ")");
        }
    }

    /**
     * Handle text node during validation.
     *
     * @param received
     * @param source
     */
    private void doText(Node received, Node source) {
        if (log.isDebugEnabled()) {
            log.debug("Validating node value for element: " + received.getParentNode());
        }

        if (received.getNodeValue() != null) {
            Assert.isTrue(source.getNodeValue() != null,
                    ValidationUtils.buildValueMismatchErrorMessage("Node value not equal for element '"
                            + received.getParentNode().getLocalName() + "'", null, received.getNodeValue().trim()));

            Assert.isTrue(received.getNodeValue().trim().equals(source.getNodeValue().trim()),
                    ValidationUtils.buildValueMismatchErrorMessage("Node value not equal for element '"
                            + received.getParentNode().getLocalName() + "'", source.getNodeValue().trim(),
                            received.getNodeValue().trim()));
        } else {
            Assert.isTrue(source.getNodeValue() == null,
                    ValidationUtils.buildValueMismatchErrorMessage("Node value not equal for element '"
                            + received.getParentNode().getLocalName() + "'", source.getNodeValue().trim(), null));
        }

        if (log.isDebugEnabled()) {
            log.debug("Node value '" + received.getNodeValue().trim() + "': OK");
        }
    }

    /**
     * Handle attribute node during validation.
     *
     * @param receivedElement
     * @param receivedAttribute
     * @param sourceElement
     * @param validationContext
     */
    private void doAttribute(Node receivedElement, Node receivedAttribute, Node sourceElement,
            XmlMessageValidationContext validationContext, NamespaceContext namespaceContext, TestContext context) {
        if (receivedAttribute.getNodeName().startsWith(XMLConstants.XMLNS_ATTRIBUTE)) { return; }

        String receivedAttributeName = receivedAttribute.getLocalName();

        if (log.isDebugEnabled()) {
            log.debug("Validating attribute: " + receivedAttributeName + " (" + receivedAttribute.getNamespaceURI() + ")");
        }

        NamedNodeMap sourceAttributes = sourceElement.getAttributes();
        Node sourceAttribute = sourceAttributes.getNamedItemNS(receivedAttribute.getNamespaceURI(), receivedAttributeName);

        Assert.isTrue(sourceAttribute != null,
                "Attribute validation failed for element '"
                        + receivedElement.getLocalName() + "', unknown attribute "
                        + receivedAttributeName + " (" + receivedAttribute.getNamespaceURI() + ")");

        if (XmlValidationUtils.isAttributeIgnored(receivedElement, receivedAttribute, sourceAttribute, validationContext.getIgnoreExpressions(), namespaceContext)) {
            return;
        }

        String receivedValue = receivedAttribute.getNodeValue();
        String sourceValue = sourceAttribute.getNodeValue();
        if (isValidationMatcherExpression(sourceAttribute)) {
            ValidationMatcherUtils.resolveValidationMatcher(sourceAttribute.getNodeName(),
                    receivedAttribute.getNodeValue().trim(),
                    sourceAttribute.getNodeValue().trim(),
                    context);
        } else if (receivedValue.contains(":") && sourceValue.contains(":")) {
            doNamespaceQualifiedAttributeValidation(receivedElement, receivedAttribute, sourceElement, sourceAttribute);
        } else {
            Assert.isTrue(receivedValue.equals(sourceValue),
                    ValidationUtils.buildValueMismatchErrorMessage("Values not equal for attribute '"
                            + receivedAttributeName + "'", sourceValue, receivedValue));
        }

        if (log.isDebugEnabled()) {
            log.debug("Attribute '" + receivedAttributeName + "'='" + receivedValue + "': OK");
        }
    }

    /**
     * Perform validation on namespace qualified attribute values if present. This includes the validation of namespace presence
     * and equality.
     * @param receivedElement
     * @param receivedAttribute
     * @param sourceElement
     * @param sourceAttribute
     */
    private void doNamespaceQualifiedAttributeValidation(Node receivedElement, Node receivedAttribute, Node sourceElement, Node sourceAttribute) {
        String receivedValue = receivedAttribute.getNodeValue();
        String sourceValue = sourceAttribute.getNodeValue();

        if (receivedValue.contains(":") && sourceValue.contains(":")) {
            // value has namespace prefix set, do special QName validation
            String receivedPrefix = receivedValue.substring(0, receivedValue.indexOf(':'));
            String sourcePrefix = sourceValue.substring(0, sourceValue.indexOf(':'));

            Map<String, String> receivedNamespaces = XMLUtils.lookupNamespaces(receivedAttribute.getOwnerDocument());
            receivedNamespaces.putAll(XMLUtils.lookupNamespaces(receivedElement));

            if (receivedNamespaces.containsKey(receivedPrefix)) {
                Map<String, String> sourceNamespaces = XMLUtils.lookupNamespaces(sourceAttribute.getOwnerDocument());
                sourceNamespaces.putAll(XMLUtils.lookupNamespaces(sourceElement));

                if (sourceNamespaces.containsKey(sourcePrefix)) {
                    Assert.isTrue(sourceNamespaces.get(sourcePrefix).equals(receivedNamespaces.get(receivedPrefix)),
                            ValidationUtils.buildValueMismatchErrorMessage("Values not equal for attribute value namespace '"
                                    + receivedValue + "'", sourceNamespaces.get(sourcePrefix), receivedNamespaces.get(receivedPrefix)));

                    // remove namespace prefixes as they must not form equality
                    receivedValue = receivedValue.substring((receivedPrefix + ":").length());
                    sourceValue = sourceValue.substring((sourcePrefix + ":").length());
                } else {
                    throw new ValidationException("Received attribute value '" + receivedAttribute.getLocalName() + "' describes namespace qualified attribute value," +
                            " control value '" + sourceValue + "' does not");
                }
            }
        }

        Assert.isTrue(receivedValue.equals(sourceValue),
                ValidationUtils.buildValueMismatchErrorMessage("Values not equal for attribute '"
                        + receivedAttribute.getLocalName() + "'", sourceValue, receivedValue));
    }

    /**
     * Handle comment node during validation.
     *
     * @param received
     */
    private void doComment(Node received) {
        log.info("Ignored comment node (" + received.getNodeValue() + ")");
    }

    /**
     * Handle processing instruction during validation.
     *
     * @param received
     */
    private void doPI(Node received) {
        log.info("Ignored processing instruction (" + received.getLocalName() + "=" + received.getNodeValue() + ")");
    }

    /**
     * Counts the attributenode for an element (xmlns attributes ignored)
     * @param attributesR attributesMap
     * @return number of attributes
     */
    private int countAttributes(NamedNodeMap attributesR) {
        int cntAttributes = 0;

        for (int i = 0; i < attributesR.getLength(); i++) {
            if (!attributesR.item(i).getNodeName().startsWith(XMLConstants.XMLNS_ATTRIBUTE)) {
                cntAttributes++;
            }
        }

        return cntAttributes;
    }

    @Override
    public XmlMessageValidationContext findValidationContext(List<ValidationContext> validationContexts) {
        for (ValidationContext validationContext : validationContexts) {
            if (validationContext instanceof XmlMessageValidationContext) {
                return (XmlMessageValidationContext) validationContext;
            }
        }

        return null;
    }

    /**
     * Checks whether the given node contains a validation matcher
     * @param node
     * @return true if node value contains validation matcher, false if not
     */
    private boolean isValidationMatcherExpression(Node node) {
        switch (node.getNodeType()) {
            case Node.ELEMENT_NODE:
                return node.getFirstChild() != null &&
                StringUtils.hasText(node.getFirstChild().getNodeValue()) &&
                ValidationMatcherUtils.isValidationMatcherExpression(node.getFirstChild().getNodeValue().trim());

            case Node.ATTRIBUTE_NODE:
                return StringUtils.hasText(node.getNodeValue()) &&
                ValidationMatcherUtils.isValidationMatcherExpression(node.getNodeValue().trim());

            default: return false; //validation matchers makes no sense
        }
    }

    @Override
    public boolean supportsMessageType(String messageType, Message message) {
        return messageType.equalsIgnoreCase(MessageType.XML.toString());
    }

    /**
     * Set the schema repository holding all known schema definition files.
     * @param schemaRepository the schemaRepository to set
     */
    public void addSchemaRepository(XsdSchemaRepository schemaRepository) {
        if (schemaRepositories == null) {
            schemaRepositories = new ArrayList<XsdSchemaRepository>();
        }
        
        schemaRepositories.add(schemaRepository);
    }

    /**
     * {@inheritDoc}
     */
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
