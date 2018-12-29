package ca.uhn.fhir.jpa.empi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.List;

import org.hl7.fhir.dstu3.model.codesystems.SearchComparator;
import org.hl7.fhir.instance.model.Conformance.SearchModifierCode;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Person;
import org.hl7.fhir.r4.model.Practitioner;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import com.google.gson.JsonObject;

import ca.uhn.fhir.jpa.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.dao.SearchParameterMap;
import ca.uhn.fhir.jpa.dao.r4.BaseJpaR4Test;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.NumberParam;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.util.TestUtil;

public class EmpiEidInterceptorPractitionerR4Test2 extends BaseJpaR4Test {

    private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(EmpiEidInterceptorPractitionerR4Test2.class);

    private static final String ENTERPRISE_IDENTIFIER_SYSTEM = "http://hapi.fhir.org/enterprise-identifier";
    private static final String EMPI_TAG_SYSTEM = "http://hapi.fhir.org/empi-tag";

    private EmpiEidInterceptorR4 myInterceptor; 
    
    @Autowired
    @Qualifier("myPersonDaoR4")
    private IFhirResourceDao<Person> myPersonDao;
    
    @After
    public void after() {
        myDaoConfig.getInterceptors().remove(myInterceptor);
    }

    @Before
    public void before() {
        
        JsonObject  theMatchCriteria = new JsonObject();
        JsonObject thePersonMatchCriteria = new JsonObject();
        theMatchCriteria.add("Person", thePersonMatchCriteria);
        thePersonMatchCriteria.addProperty(Person.SP_NAME, SearchModifierCode.EXACT.toString());
        thePersonMatchCriteria.addProperty(Person.SP_BIRTHDATE, SearchComparator.EQ.toString());
                
        myInterceptor = new EmpiEidInterceptorR4(ENTERPRISE_IDENTIFIER_SYSTEM, EMPI_TAG_SYSTEM, theMatchCriteria.toString());

        myInterceptor.setMyPatientDao(myPatientDao);
        myInterceptor.setMyPersonDao(myPersonDao);
        myInterceptor.setMyPractitionerDao(myPractitionerDao);
        myDaoConfig.getInterceptors().add(myInterceptor);
    }

    @Test
    // -- Create a Practitioner, can't find matched Person
    public void testJpaCreatePractitionertWithoutMatchedPerson() {

        Practitioner p = new Practitioner();
        p.getNameFirstRep().setFamily("foo");
        p.getNameFirstRep().addGivenElement().setValue("bar");
        p.getBirthDateElement().setValueAsString("2000-01-01");

        MethodOutcome methodOutcome = myPractitionerDao.create(p);
        Practitioner createdPractitioner = (Practitioner) methodOutcome.getResource();
        
        SearchParameterMap theParams = new SearchParameterMap();
        ReferenceParam refParam = new ReferenceParam();
        refParam.setValue(methodOutcome.getId().getValueAsString());
        theParams.add(Person.SP_LINK, refParam);
        
        IBundleProvider provider = myPersonDao.search(theParams);
        List<IBaseResource> theOtherPatientList = provider.getResources(0, 1);
        
        Person createdPerson = (Person)theOtherPatientList.get(0);
        
        // 1. verify the Patient is created
        assertEquals("foo", createdPractitioner.getNameFirstRep().getFamily());
        
        // 2. verify the Person is created with same name
        assertEquals("foo", createdPerson.getNameFirstRep().getFamily());
        
        // 3. verify eid is created
        assertEquals(ENTERPRISE_IDENTIFIER_SYSTEM, createdPerson.getIdentifierFirstRep().getSystem());       
        assertNotNull(createdPerson.getIdentifierFirstRep().getValue());
        
        // 4. verify they are linked
        assertEquals("Practitioner/"+methodOutcome.getId().getIdPart(), createdPerson.getLinkFirstRep().getTarget().getReference());   
        ourLog.debug(createdPerson.getId());
    }
    
    @Test
    // -- Create a Practitioner, find one matched Person
    public void testJpaCreatePractitionerWithOneMatchedPerson() {

        // 1. create Person
        Person thePerson = new Person();
        thePerson.getNameFirstRep().setFamily("foo");
        thePerson.getNameFirstRep().addGivenElement().setValue("bar");
        thePerson.getBirthDateElement().setValueAsString("2000-01-01");

        MethodOutcome personOutcome = myPersonDao.create(thePerson);
        Person createdPerson = (Person) personOutcome.getResource();
        
        // 2. create the Practitioner
        Practitioner thePractioner = new Practitioner();
        thePractioner.getNameFirstRep().setFamily("foo");
        thePractioner.getNameFirstRep().addGivenElement().setValue("bar");
        thePractioner.getBirthDateElement().setValueAsString("2000-01-01");

        MethodOutcome thePractitionerOutcome = myPractitionerDao.create(thePractioner);
        Practitioner createdPractitioner = (Practitioner) thePractitionerOutcome.getResource();
        
        // 1. verify the Person is created
        assertEquals("foo", createdPerson.getNameFirstRep().getFamily());
        
        // 2. verify the Patient is created
        assertEquals("foo", createdPractitioner.getNameFirstRep().getFamily());
        
        // 3. verify eid is created on the Person
        assertEquals(ENTERPRISE_IDENTIFIER_SYSTEM, createdPerson.getIdentifierFirstRep().getSystem());       
        assertNotNull(createdPerson.getIdentifierFirstRep().getValue());
        
        // 4. verify they are linked
        SearchParameterMap theParams = new SearchParameterMap();
        theParams.add(Person.SP_RES_ID, new NumberParam(personOutcome.getId().getIdPart()));
        
        IBundleProvider provider = myPersonDao.search(theParams);
        List<IBaseResource> theOtherPatientList = provider.getResources(0, provider.size());
        Person theUpdatedPerson = (Person)theOtherPatientList.get(0);
        
        assertEquals("Practitioner/"+thePractitionerOutcome.getId().getIdPart(), theUpdatedPerson.getLinkFirstRep().getTarget().getReference());        
    }

    @Test
    //-- create a practitioner, but there are two person with same name
    public void testJpaCreatePatientWithTwoMatchedPerson() {

        // 1. create first person
        Person thePerson1 = new Person();
        thePerson1.getNameFirstRep().setFamily("foo");
        thePerson1.getNameFirstRep().addGivenElement().setValue("bar");
        thePerson1.getBirthDateElement().setValueAsString("2000-01-01");
        MethodOutcome person1Outcome = myPersonDao.create(thePerson1);
        Person createdPerson1 = (Person) person1Outcome.getResource();
        assertEquals("foo", createdPerson1.getNameFirstRep().getFamily());
                            
        //2. create the second person
        Person thePerson2 = new Person();
        thePerson2.getNameFirstRep().setFamily("foo");
        thePerson2.getNameFirstRep().addGivenElement().setValue("bar");
        thePerson2.getBirthDateElement().setValueAsString("2000-01-01");

        MethodOutcome person2Outcome = myPersonDao.create(thePerson2, mySrd);
        Person createdPerson2 = (Person) person2Outcome.getResource();
        assertEquals("foo", createdPerson2.getNameFirstRep().getFamily());
        
        // 3. create the Practitioner
        Practitioner thePractitioner = new Practitioner();
        thePractitioner.getNameFirstRep().setFamily("foo");
        thePractitioner.getNameFirstRep().addGivenElement().setValue("bar");
        thePractitioner.getBirthDateElement().setValueAsString("2000-01-01");

        MethodOutcome thePractionerOutcome = myPractitionerDao.create(thePractitioner);
        Practitioner createdPractitioenr = (Practitioner) thePractionerOutcome.getResource();
        
        Coding theTag = createdPractitioenr.getMeta().getTagFirstRep();
        
        //-- verify the tag is create on the patient
        assertEquals(EMPI_TAG_SYSTEM, theTag.getSystem());
        assertEquals(EmpiEidInterceptorR4.TAG_DUP_PERSON, theTag.getCode());        
        assertEquals("Same name birthdate found.", theTag.getDisplay());         
    }
    
    @Test
    public void testJpaUpdatePractitioner() {

        Practitioner p = new Practitioner();
        p.getNameFirstRep().setFamily("foo");
        p.getNameFirstRep().addGivenElement().setValue("bar");
        p.getBirthDateElement().setValueAsString("2000-01-01");

        MethodOutcome methodOutcome = myPractitionerDao.create(p);
        Practitioner createdPractitioner = (Practitioner) methodOutcome.getResource();
               
        // 1. verify the Patient is created
        assertEquals("foo", createdPractitioner.getNameFirstRep().getFamily());
       
        // 2. the patient is updated (last name changed
        createdPractitioner.getNameFirstRep().setFamily("Smith");
        myPractitionerDao.update(createdPractitioner);
        
        // 3. search the Person by link
        SearchParameterMap theParams = new SearchParameterMap();
        ReferenceParam refParam = new ReferenceParam();
        refParam.setValue(methodOutcome.getId().getValueAsString());
        theParams.add(Person.SP_LINK, refParam);
        
        IBundleProvider provider = myPersonDao.search(theParams);
        List<IBaseResource> theOtherPersonList = provider.getResources(0, 1);
        
        Person matchedPerson = (Person)theOtherPersonList.get(0);
               
        assertEquals(ENTERPRISE_IDENTIFIER_SYSTEM, matchedPerson.getIdentifierFirstRep().getSystem());       
        assertNotNull(matchedPerson.getIdentifierFirstRep().getValue());
    }
    
    @AfterClass
    public static void afterClassClearContext() {
        TestUtil.clearAllStaticFieldsForUnitTest();
    }

}
