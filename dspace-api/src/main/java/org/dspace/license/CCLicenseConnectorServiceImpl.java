/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.license;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.Logger;
import org.dspace.services.ConfigurationService;
import org.jaxen.JaxenException;
import org.jaxen.jdom.JDOMXPath;
import org.jdom.Attribute;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.xml.sax.InputSource;

/**
 * Implementation for the Creative commons license connector service.
 * This class is responsible for all the calls to the CC license API and parsing the response
 */
public class CCLicenseConnectorServiceImpl implements CCLicenseConnectorService, InitializingBean {

    private Logger log = org.apache.logging.log4j.LogManager.getLogger(CCLicenseConnectorServiceImpl.class);

    private CloseableHttpClient client;
    private SAXBuilder parser = new SAXBuilder();

    private String postArgument = "answers";
    private String postAnswerFormat =
            "<answers> " +
                    "<locale>{1}</locale>" +
                    "<license-{0}>" +
                    "{2}" +
                    "</license-{0}>" +
                    "</answers>";


    @Autowired
    private ConfigurationService configurationService;

    @Override
    public void afterPropertiesSet() throws Exception {
        HttpClientBuilder builder = HttpClientBuilder.create();

        client = builder
                .disableAutomaticRetries()
                .setMaxConnTotal(5)
                .build();
    }

    /**
     * Retrieves the CC Licenses for the provided language from the CC License API
     *
     * @param language - the language to retrieve the licenses for
     * @return a map of licenses with the id and the license for the provided language
     */
    public Map<String, CCLicense> retrieveLicenses(String language) {
        String ccLicenseUrl = configurationService.getProperty("cc.api.rooturl");

        String uri = ccLicenseUrl + "/?locale=" + language;
        HttpGet httpGet = new HttpGet(uri);

        List<String> licenses;
        try (CloseableHttpResponse response = client.execute(httpGet)) {
            licenses = retrieveLicenses(response);
        } catch (JDOMException | JaxenException | IOException e) {
            log.error("Error while retrieving the license details using url: " + uri, e);
            licenses = Collections.emptyList();
        }

        Map<String, CCLicense> ccLicenses = new HashMap<>();

        for (String license : licenses) {

            String licenseUri = ccLicenseUrl + "/license/" + license;
            HttpGet licenseHttpGet = new HttpGet(licenseUri);
            try (CloseableHttpResponse response = client.execute(licenseHttpGet)) {
                CCLicense ccLicense = retrieveLicenseObject(license, response);
                ccLicenses.put(ccLicense.getLicenseId(), ccLicense);
            } catch (JaxenException | JDOMException | IOException e) {
                log.error("Error while retrieving the license details using url: " + licenseUri, e);
            }
        }

        return ccLicenses;
    }

    /**
     * Retrieve the list of licenses from the response from the CC License API and remove the licenses configured
     * to be excluded
     *
     * @param response The response from the API
     * @return a list of license identifiers for which details need to be retrieved
     * @throws IOException
     * @throws JaxenException
     * @throws JDOMException
     */
    private List<String> retrieveLicenses(CloseableHttpResponse response)
            throws IOException, JaxenException, JDOMException {

        List<String> domains = new LinkedList<>();
        String[] excludedLicenses = configurationService.getArrayProperty("cc.license.classfilter");


        String responseString = EntityUtils.toString(response.getEntity());
        JDOMXPath licenseClassXpath = new JDOMXPath("//licenses/license");


        try (StringReader stringReader = new StringReader(responseString)) {
            InputSource is = new InputSource(stringReader);
            org.jdom.Document classDoc = this.parser.build(is);

            List<Element> elements = licenseClassXpath.selectNodes(classDoc);
            for (Element element : elements) {
                String licenseId = getSingleNodeValue(element, "@id");
                if (StringUtils.isNotBlank(licenseId) && !ArrayUtils.contains(excludedLicenses, licenseId)) {
                    domains.add(licenseId);
                }
            }
        }

        return domains;

    }

    /**
     * Parse the response for a single CC License and return the corresponding CC License Object
     *
     * @param licenseId the license id of the CC License to retrieve
     * @param response  for a specific CC License response
     * @return the corresponding CC License Object
     * @throws IOException
     * @throws JaxenException
     * @throws JDOMException
     */
    private CCLicense retrieveLicenseObject(final String licenseId, CloseableHttpResponse response)
            throws IOException, JaxenException, JDOMException {

        String responseString = EntityUtils.toString(response.getEntity());


        JDOMXPath licenseClassXpath = new JDOMXPath("//licenseclass");
        JDOMXPath licenseFieldXpath = new JDOMXPath("field");


        try (StringReader stringReader = new StringReader(responseString)) {
            InputSource is = new InputSource(stringReader);

            org.jdom.Document classDoc = this.parser.build(is);

            Object element = licenseClassXpath.selectSingleNode(classDoc);
            String licenseLabel = getSingleNodeValue(element, "label");

            List<CCLicenseField> ccLicenseFields = new LinkedList<>();

            List<Element> licenseFields = licenseFieldXpath.selectNodes(element);
            for (Element licenseField : licenseFields) {
                CCLicenseField ccLicenseField = parseLicenseField(licenseField);
                ccLicenseFields.add(ccLicenseField);
            }

            return new CCLicense(licenseId, licenseLabel, ccLicenseFields);
        }
    }

    private CCLicenseField parseLicenseField(final Element licenseField) throws JaxenException {
        String id = getSingleNodeValue(licenseField, "@id");
        String label = getSingleNodeValue(licenseField, "label");
        String description = getSingleNodeValue(licenseField, "description");

        JDOMXPath enumXpath = new JDOMXPath("enum");
        List<Element> enums = enumXpath.selectNodes(licenseField);

        List<CCLicenseFieldEnum> ccLicenseFieldEnumList = new LinkedList<>();

        for (Element enumElement : enums) {
            CCLicenseFieldEnum ccLicenseFieldEnum = parseEnum(enumElement);
            ccLicenseFieldEnumList.add(ccLicenseFieldEnum);
        }

        return new CCLicenseField(id, label, description, ccLicenseFieldEnumList);

    }

    private CCLicenseFieldEnum parseEnum(final Element enumElement) throws JaxenException {
        String id = getSingleNodeValue(enumElement, "@id");
        String label = getSingleNodeValue(enumElement, "label");
        String description = getSingleNodeValue(enumElement, "description");

        return new CCLicenseFieldEnum(id, label, description);
    }


    private String getNodeValue(final Object el) {
        if (el instanceof Element) {
            return ((Element) el).getValue();
        } else if (el instanceof Attribute) {
            return ((Attribute) el).getValue();
        } else if (el instanceof String) {
            return (String) el;
        } else {
            return null;
        }
    }

    private String getSingleNodeValue(final Object t, String query) throws JaxenException {
        JDOMXPath xpath = new JDOMXPath(query);
        Object singleNode = xpath.selectSingleNode(t);

        return getNodeValue(singleNode);
    }

    /**
     * Retrieve the CC License URI based on the provided license id, language and answers to the field questions from
     * the CC License API
     *
     * @param licenseId - the ID of the license
     * @param language  - the language for which to retrieve the full answerMap
     * @param answerMap - the answers to the different field questions
     * @return the CC License URI
     */
    public String retrieveRightsByQuestion(String licenseId,
                                           String language,
                                           Map<String, String> answerMap) {

        String ccLicenseUrl = configurationService.getProperty("cc.api.rooturl");


        HttpPost httpPost = new HttpPost(ccLicenseUrl + "/license/" + licenseId + "/issue");


        String answers = createAnswerString(answerMap);
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        String text = MessageFormat.format(postAnswerFormat, licenseId, language, answers);
        builder.addTextBody(postArgument, text);

        HttpEntity multipart = builder.build();

        httpPost.setEntity(multipart);

        try (CloseableHttpResponse response = client.execute(httpPost)) {
            return retrieveLicenseUri(response);
        } catch (JDOMException | JaxenException | IOException e) {
            log.error("Error while retrieving the license uri for license : " + licenseId + " with answers "
                              + answerMap.toString(), e);
        }
        return null;
    }

    /**
     * Parse the response for the CC License URI request and return the corresponding CC License URI
     *
     * @param response for a specific CC License URI response
     * @return the corresponding CC License URI as a string
     * @throws IOException
     * @throws JaxenException
     * @throws JDOMException
     */
    private String retrieveLicenseUri(final CloseableHttpResponse response)
            throws IOException, JaxenException, JDOMException {

        String responseString = EntityUtils.toString(response.getEntity());
        JDOMXPath licenseClassXpath = new JDOMXPath("//result/license-uri");


        try (StringReader stringReader = new StringReader(responseString)) {
            InputSource is = new InputSource(stringReader);
            org.jdom.Document classDoc = this.parser.build(is);

            Object node = licenseClassXpath.selectSingleNode(classDoc);
            String nodeValue = getNodeValue(node);

            if (StringUtils.isNotBlank(nodeValue)) {
                return nodeValue;
            }
        }
        return null;
    }

    private String createAnswerString(final Map<String, String> parameterMap) {
        StringBuilder sb = new StringBuilder();
        for (String key : parameterMap.keySet()) {
            sb.append("<");
            sb.append(key);
            sb.append(">");
            sb.append(parameterMap.get(key));
            sb.append("</");
            sb.append(key);
            sb.append(">");
        }
        return sb.toString();
    }

    /**
     * Retrieve the license RDF document based on the license URI
     *
     * @param licenseURI - The license URI for which to retrieve the license RDF document
     * @return the license RDF document
     * @throws IOException
     */
    @Override
    public Document retrieveLicenseRDFDoc(String licenseURI) throws IOException {
        String ccLicenseUrl = configurationService.getProperty("cc.api.rooturl");

        String issueUrl = ccLicenseUrl + "/details?license-uri=" + licenseURI;

        URL request_url;
        try {
            request_url = new URL(issueUrl);
        } catch (MalformedURLException e) {
            return null;
        }
        URLConnection connection = request_url.openConnection();
        connection.setDoOutput(true);
        try {
            // parsing document from input stream
            InputStream stream = connection.getInputStream();
            Document doc = parser.build(stream);
            return doc;

        } catch (Exception e) {
            log.error("Error while retrieving the license document for URI: " + licenseURI, e);
        }
        return null;
    }

    /**
     * Retrieve the license Name from the license document
     *
     * @param doc - The license document from which to retrieve the license name
     * @return the license name
     */
    public String retrieveLicenseName(final Document doc) {
        try {
            return getSingleNodeValue(doc, "//result/license-uri");
        } catch (JaxenException e) {
            log.error("Error while retrieving the license name from the license document", e);
        }
        return null;
    }

}
