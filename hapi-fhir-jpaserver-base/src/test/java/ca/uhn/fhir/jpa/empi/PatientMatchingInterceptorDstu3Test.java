package ca.uhn.fhir.jpa.empi;

import static org.junit.Assert.assertEquals;

import org.hl7.fhir.dstu3.model.Patient;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ca.uhn.fhir.jpa.dao.dstu3.BaseJpaDstu3Test;
import ca.uhn.fhir.jpa.search.PersistedJpaBundleProvider;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.util.TestUtil;

public class PatientMatchingInterceptorDstu3Test extends BaseJpaDstu3Test {

    private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(PatientMatchingInterceptorDstu3Test.class);

    private static final String MASTER_PATIENT_INDEX_SYSTEM = "http://hapi.fhir.org/master-patient-index";

    private PatientMatchingInterceptorDstu3 myPatientInterceptor;

    @After
    public void after() {
        myDaoConfig.getInterceptors().remove(myPatientInterceptor);
        
        
    }

    @Before
    public void before() {
        // myPatientInterceptor = mock(PatientMatchingInterceptorDstu3.class);
        myPatientInterceptor = new PatientMatchingInterceptorDstu3(MASTER_PATIENT_INDEX_SYSTEM);
        myPatientInterceptor.setDao(myPatientDao);
        // myPatientInterceptor.setMyMasterPatientIndexSystem(MASTER_PATIENT_INDEX_SYSTEM);

        myDaoConfig.getInterceptors().add(myPatientInterceptor);
    }

    // -- This one does not work yet
    @Test
    public void testJpaCreateWithEmpi() {

        Patient p = new Patient();
        p.getNameFirstRep().setFamily("foo");
        p.getNameFirstRep().addGivenElement().setValue("bar");
        p.getBirthDateElement().setValueAsString("2000-01-01");

        MethodOutcome methodOutcome = myPatientDao.create(p);
        Patient createdPatient = (Patient) methodOutcome.getResource();

        assertEquals("foo", createdPatient.getNameFirstRep().getFamily());
        assertEquals(MASTER_PATIENT_INDEX_SYSTEM, createdPatient.getIdentifierFirstRep().getSystem());
    }

    @AfterClass
    public static void afterClassClearContext() {
        TestUtil.clearAllStaticFieldsForUnitTest();
    }

}
