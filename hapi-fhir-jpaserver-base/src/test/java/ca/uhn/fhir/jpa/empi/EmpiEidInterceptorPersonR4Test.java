package ca.uhn.fhir.jpa.empi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.List;

import org.hl7.fhir.dstu3.model.codesystems.SearchComparator;
import org.hl7.fhir.instance.model.Conformance.SearchModifierCode;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Person;
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
import ca.uhn.fhir.util.TestUtil;

public class EmpiEidInterceptorPersonR4Test extends BaseJpaR4Test {

    private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(EmpiEidInterceptorPersonR4Test.class);

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
        //thePersonMatchCriteria.addProperty(Person.SP_GENDER, SearchModifierCode.EXACT.toString() );
                
        myInterceptor = new EmpiEidInterceptorR4(ENTERPRISE_IDENTIFIER_SYSTEM, EMPI_TAG_SYSTEM, theMatchCriteria.toString());

        myInterceptor.setMyPatientDao(myPatientDao);
        myInterceptor.setMyPersonDao(myPersonDao);
        myInterceptor.setMyPractitionerDao(myPractitionerDao);
        myDaoConfig.getInterceptors().add(myInterceptor);
    }

    @Test
    //-- create a new Person, check EID is injected
    public void testJpaCreateNewPerson() {

        Person thePerson = new Person();
        thePerson.getNameFirstRep().setFamily("foo");
        thePerson.getNameFirstRep().addGivenElement().setValue("bar");
        thePerson.getBirthDateElement().setValueAsString("2000-01-01");

        MethodOutcome personOutcome = myPersonDao.create(thePerson);
        Person createdPerson = (Person) personOutcome.getResource();
                
        // 1. verify the Person is created
        assertEquals("foo", createdPerson.getNameFirstRep().getFamily());
        
        // 2. verify EID is injected 
        assertEquals(ENTERPRISE_IDENTIFIER_SYSTEM, createdPerson.getIdentifierFirstRep().getSystem());       
        assertNotNull(createdPerson.getIdentifierFirstRep().getValue());
        ourLog.debug(createdPerson.getId());
    }
    
    @Test
    //-- create a new Person, check EID is injected
    public void testJpaCreateNewPersonWithoutBirthDate() {

        Person thePerson = new Person();
        thePerson.getNameFirstRep().setFamily("foo");
        thePerson.getNameFirstRep().addGivenElement().setValue("bar");
        thePerson.getBirthDateElement().setValueAsString("2000-01-01");

        MethodOutcome personOutcome = myPersonDao.create(thePerson);
        Person createdPerson = (Person) personOutcome.getResource();
                
        // 1. verify the Person is created
        assertEquals("foo", createdPerson.getNameFirstRep().getFamily());
        
        // 2. verify EID is injected 
        assertEquals(ENTERPRISE_IDENTIFIER_SYSTEM, createdPerson.getIdentifierFirstRep().getSystem());       
        assertNotNull(createdPerson.getIdentifierFirstRep().getValue());
        ourLog.debug(createdPerson.getId());
    }

    @Test
    //-- create two person with same name, and birth date
    public void testJpaCreateSamePerson() {

        Person thePerson1 = new Person();
        thePerson1.getNameFirstRep().setFamily("foo");
        thePerson1.getNameFirstRep().addGivenElement().setValue("bar");
        thePerson1.getBirthDateElement().setValueAsString("2000-01-01");

        MethodOutcome person1Outcome = myPersonDao.create(thePerson1);
        Person createdPerson1 = (Person) person1Outcome.getResource();
               
        // 1. verify the Person1 is created
        assertEquals("foo", createdPerson1.getNameFirstRep().getFamily());
        
        // 2. verify EID is injected 
        assertEquals(ENTERPRISE_IDENTIFIER_SYSTEM, createdPerson1.getIdentifierFirstRep().getSystem());       
        assertNotNull(createdPerson1.getIdentifierFirstRep().getValue());
        //System.out.println(createdPerson1.getId());
        
        //-- second person
        Person thePerson2 = new Person();
        thePerson2.getNameFirstRep().setFamily("foo");
        thePerson2.getNameFirstRep().addGivenElement().setValue("bar");
        thePerson2.getBirthDateElement().setValueAsString("2000-01-01");

        MethodOutcome person2Outcome = myPersonDao.create(thePerson2, mySrd);
        Person createdPerson2 = (Person) person2Outcome.getResource();
        
        // 3. verify the dupTag is injected to the second person
        Coding theTag = createdPerson2.getMeta().getTagFirstRep();
        assertEquals(EMPI_TAG_SYSTEM, theTag.getSystem());
        assertEquals("dupPerson", theTag.getCode());        
        assertEquals("Same name birthdate found.", theTag.getDisplay());
        
        // 4. verify the dupTag is injected to the first person too
        SearchParameterMap theParams = new SearchParameterMap();
        theParams.add(Person.SP_RES_ID, new NumberParam(person1Outcome.getId().getIdPart()));
        
        IBundleProvider provider = myPersonDao.search(theParams);
        List<IBaseResource> theOtherPatientList = provider.getResources(0, provider.size());
        Person theUpdatedPerson = (Person)theOtherPatientList.get(0);
        Coding theTag2 = theUpdatedPerson.getMeta().getTagFirstRep();
        
        assertEquals(EMPI_TAG_SYSTEM, theTag2.getSystem());
        assertEquals(EmpiEidInterceptorR4.TAG_DUP_PERSON, theTag2.getCode());        
        assertEquals("Same name birthdate found.", theTag2.getDisplay());  
    }
    
    @Test
    //-- create two person with same name, and birth date
    public void testJpaCreateSamePersonWihtoutBirthDate() {

        Person thePerson1 = new Person();
        thePerson1.getNameFirstRep().setFamily("foo");
        thePerson1.getNameFirstRep().addGivenElement().setValue("bar");
        
        MethodOutcome person1Outcome = myPersonDao.create(thePerson1);
        Person createdPerson1 = (Person) person1Outcome.getResource();
               
        // 1. verify the Person1 is created
        assertEquals("foo", createdPerson1.getNameFirstRep().getFamily());
        
        // 2. verify EID is injected 
        assertEquals(ENTERPRISE_IDENTIFIER_SYSTEM, createdPerson1.getIdentifierFirstRep().getSystem());       
        assertNotNull(createdPerson1.getIdentifierFirstRep().getValue());
        //System.out.println(createdPerson1.getId());
        
        //-- second person
        Person thePerson2 = new Person();
        thePerson2.getNameFirstRep().setFamily("foo");
        thePerson2.getNameFirstRep().addGivenElement().setValue("bar");
        
        MethodOutcome person2Outcome = myPersonDao.create(thePerson2, mySrd);
        Person createdPerson2 = (Person) person2Outcome.getResource();
        
        // 3. verify no tag
        Coding theTag = createdPerson2.getMeta().getTagFirstRep();
        assertNull(theTag.getSystem());
        assertNull(theTag.getCode());  
        
        // 4. verify two eid is different
        String firstEid = createdPerson1.getIdentifierFirstRep().getValue();
        String secondEid = createdPerson2.getIdentifierFirstRep().getValue();
        assertNotEquals(firstEid, secondEid);
    }

    @Test
    //-- updated existing Person, the old person with EID
    public void testJpaUpdatePerson() {

        Person thePerson = new Person();
        thePerson.getNameFirstRep().setFamily("foo");
        thePerson.getNameFirstRep().addGivenElement().setValue("bar");
        thePerson.getBirthDateElement().setValueAsString("2000-01-01");

        MethodOutcome personOutcome = myPersonDao.create(thePerson);
        Person createdPerson = (Person) personOutcome.getResource();
               
        // 1. verify the Person is created
        assertEquals("foo", createdPerson.getNameFirstRep().getFamily());
       
        // 2. verify EID is injected 
        assertEquals(ENTERPRISE_IDENTIFIER_SYSTEM, createdPerson.getIdentifierFirstRep().getSystem());  
        String eid = createdPerson.getIdentifierFirstRep().getValue();
        assertNotNull(eid);
     
        //-- change the last name
        createdPerson.getNameFirstRep().setFamily("smith");
        myPersonDao.update(createdPerson);
        
        // 3. verify the EID is copied
        SearchParameterMap theParams = new SearchParameterMap();        
        theParams.add(Person.SP_RES_ID, new NumberParam(personOutcome.getId().getIdPart()));
        
        IBundleProvider provider = myPersonDao.search(theParams);
        List<IBaseResource> theOtherPatientList = provider.getResources(0, provider.size());
        Person theUpdatedPerson = (Person)theOtherPatientList.get(0);
        
        assertEquals("smith", theUpdatedPerson.getNameFirstRep().getFamily());
        assertEquals(ENTERPRISE_IDENTIFIER_SYSTEM, theUpdatedPerson.getIdentifierFirstRep().getSystem());       
        assertEquals(eid, theUpdatedPerson.getIdentifierFirstRep().getValue());
    }
    
    
    @AfterClass
    public static void afterClassClearContext() {
        TestUtil.clearAllStaticFieldsForUnitTest();
    }

}
