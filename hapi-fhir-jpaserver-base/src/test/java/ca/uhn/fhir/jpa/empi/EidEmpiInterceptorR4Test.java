package ca.uhn.fhir.jpa.empi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.util.List;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Person;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import ca.uhn.fhir.jpa.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.dao.SearchParameterMap;
import ca.uhn.fhir.jpa.dao.r4.BaseJpaR4Test;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.NumberParam;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.util.TestUtil;

public class EidEmpiInterceptorR4Test extends BaseJpaR4Test {

    private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(EidEmpiInterceptorR4Test.class);

    private static final String ENTERPRISE_IDENTIFIER = "http://hapi.fhir.org/enterprise-identifier";

    private EidEmpiInterceptorR4 myInterceptor = new EidEmpiInterceptorR4(ENTERPRISE_IDENTIFIER);

    @Autowired
    @Qualifier("myPersonDaoR4")
    private IFhirResourceDao<Person> myPersonDao;
    
    @After
    public void after() {
        myDaoConfig.getInterceptors().remove(myInterceptor);
    }

    @Before
    public void before() {
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
        assertEquals(ENTERPRISE_IDENTIFIER, createdPerson.getIdentifierFirstRep().getSystem());       
        assertNotNull(createdPerson.getIdentifierFirstRep().getValue());
        System.out.println(createdPerson.getId());
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
        assertEquals(ENTERPRISE_IDENTIFIER, createdPerson1.getIdentifierFirstRep().getSystem());       
        assertNotNull(createdPerson1.getIdentifierFirstRep().getValue());
        System.out.println(createdPerson1.getId());
        
        //-- second person
        Person thePerson2 = new Person();
        thePerson2.getNameFirstRep().setFamily("foo");
        thePerson2.getNameFirstRep().addGivenElement().setValue("bar");
        thePerson2.getBirthDateElement().setValueAsString("2000-01-01");

        try {
            myPersonDao.create(thePerson2, mySrd);
            fail();
        } catch (InvalidRequestException e) {
            assertEquals("The person with same name:bar foo and birthdate:2000-01-01", e.getMessage());
        }
        
    }
    
    @Test
    // -- Create a Patient, can't find matched Person
    public void testJpaCreatePatientWithoutMatchedPerson() {

        Patient p = new Patient();
        p.getNameFirstRep().setFamily("foo");
        p.getNameFirstRep().addGivenElement().setValue("bar");
        p.getBirthDateElement().setValueAsString("2000-01-01");

        MethodOutcome methodOutcome = myPatientDao.create(p);
        Patient createdPatient = (Patient) methodOutcome.getResource();
        
        SearchParameterMap theParams = new SearchParameterMap();
        ReferenceParam refParam = new ReferenceParam();
        refParam.setValue(methodOutcome.getId().getValueAsString());
        theParams.add(Person.SP_LINK, refParam);
        
        IBundleProvider provider = myPersonDao.search(theParams);
        List<IBaseResource> theOtherPatientList = provider.getResources(0, 1);
        
        Person createdPerson = (Person)theOtherPatientList.get(0);
        
        // 1. verify the Patient is created
        assertEquals("foo", createdPatient.getNameFirstRep().getFamily());
        
        // 2. verify the Person is created with same name
        assertEquals("foo", createdPerson.getNameFirstRep().getFamily());
        
        // 3. verify eid is created
        assertEquals(ENTERPRISE_IDENTIFIER, createdPerson.getIdentifierFirstRep().getSystem());       
        assertNotNull(createdPerson.getIdentifierFirstRep().getValue());
        
        // 4. verify they are linked
        assertEquals("Patient/"+methodOutcome.getId().getIdPart(), createdPerson.getLinkFirstRep().getTarget().getReference());   
        System.out.println(createdPerson.getId());
    }
    
    @Test
    // -- Create a Patient, find one matched Person
    public void testJpaCreatePatientWithOneMatchedPerson() {

        // 1. create Person
        Person thePerson = new Person();
        thePerson.getNameFirstRep().setFamily("foo");
        thePerson.getNameFirstRep().addGivenElement().setValue("bar");
        thePerson.getBirthDateElement().setValueAsString("2000-01-01");

        MethodOutcome personOutcome = myPersonDao.create(thePerson);
        Person createdPerson = (Person) personOutcome.getResource();
        
        // 2. create the Patient
        Patient thePatient = new Patient();
        thePatient.getNameFirstRep().setFamily("foo");
        thePatient.getNameFirstRep().addGivenElement().setValue("bar");
        thePatient.getBirthDateElement().setValueAsString("2000-01-01");

        MethodOutcome patientOutcome = myPatientDao.create(thePatient);
        Patient createdPatient = (Patient) patientOutcome.getResource();
        
        // 1. verify the Person is created
        assertEquals("foo", createdPerson.getNameFirstRep().getFamily());
        
        // 2. verify the Patient is created
        assertEquals("foo", createdPatient.getNameFirstRep().getFamily());
        
        // 3. verify eid is created on the Person
        assertEquals(ENTERPRISE_IDENTIFIER, createdPerson.getIdentifierFirstRep().getSystem());       
        assertNotNull(createdPerson.getIdentifierFirstRep().getValue());
        
        // 4. verify they are linked
        SearchParameterMap theParams = new SearchParameterMap();
        theParams.add(Person.SP_RES_ID, new NumberParam(personOutcome.getId().getIdPart()));
        
        IBundleProvider provider = myPersonDao.search(theParams);
        List<IBaseResource> theOtherPatientList = provider.getResources(0, provider.size());
        Person theUpdatedPerson = (Person)theOtherPatientList.get(0);
        
        assertEquals("Patient/"+patientOutcome.getId().getIdPart(), theUpdatedPerson.getLinkFirstRep().getTarget().getReference());        
    }

    @AfterClass
    public static void afterClassClearContext() {
        TestUtil.clearAllStaticFieldsForUnitTest();
    }

}
