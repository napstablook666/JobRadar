package com.getjobs.worker.liepin;

import com.getjobs.application.entity.LiepinEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiepinSearchResponseTest {

    @Test
    void parsesExistingNestedJobCardShape() {
        Liepin.SearchParseResult result = Liepin.parseSearchEntities("""
                {"data":{"data":{"jobCardList":[
                  {"job":{"jobId":"1001","title":"Application Engineer","link":"/job/1001",
                    "salary":"8-12K","jobDesc":"Device support"},
                   "comp":{"compId":"88","compName":"Example Co"},
                   "recruiter":{"recruiterId":"r1","recruiterName":"Recruiter"}}
                ]}}}
                """);

        assertTrue(result.recognized());
        assertEquals("data.data.jobCardList", result.detail().substring("岗位列表路径=".length()));
        LiepinEntity entity = result.entities().get(0);
        assertEquals(1001L, entity.getJobId());
        assertEquals("Application Engineer", entity.getJobTitle());
        assertEquals("Device support", entity.getJobDescription());
        assertEquals(88L, entity.getCompId());
        assertEquals("Recruiter", entity.getHrName());
    }

    @Test
    void parsesAlternateEnvelopeAndDirectFields() {
        Liepin.SearchParseResult result = Liepin.parseSearchEntities("""
                {"result":{"data":{"items":[
                  {"job_id":"1002","jobTitle":"Device Support","salaryText":"9-13K",
                   "company":{"companyId":"89","companyName":"Example Labs"},
                   "hrName":"Hiring Manager"}
                ]}}}
                """);

        assertTrue(result.recognized());
        LiepinEntity entity = result.entities().get(0);
        assertEquals(1002L, entity.getJobId());
        assertEquals("Device Support", entity.getJobTitle());
        assertEquals("9-13K", entity.getJobSalaryText());
        assertEquals(89L, entity.getCompId());
        assertEquals("Hiring Manager", entity.getHrName());
    }

    @Test
    void findsNestedRecordsWithPositionId() {
        Liepin.SearchParseResult result = Liepin.parseSearchEntities("""
                {"wrapper":{"payload":{"records":[
                  {"job":{"positionId":1003,"jobName":"Field Specialist"}}
                ]}}}
                """);

        assertTrue(result.recognized());
        assertEquals(1003L, result.entities().get(0).getJobId());
        assertEquals("Field Specialist", result.entities().get(0).getJobTitle());
    }

    @Test
    void acceptsKnownEmptyJobList() {
        Liepin.SearchParseResult result = Liepin.parseSearchEntities(
                "{\"data\":{\"jobCardList\":[]}}");

        assertTrue(result.recognized());
        assertTrue(result.entities().isEmpty());
    }

    @Test
    void rejectsJsonWithoutAJobRecordList() {
        Liepin.SearchParseResult result = Liepin.parseSearchEntities(
                "{\"data\":{\"items\":[{\"recommendationId\":1}]}}");

        assertFalse(result.recognized());
        assertTrue(result.detail().contains("岗位列表未找到"));
        assertTrue(result.detail().contains("顶层字段=data"));
    }

    @Test
    void rejectsBlankHtmlAndMalformedJson() {
        assertFalse(Liepin.parseSearchEntities(" ").recognized());
        assertFalse(Liepin.parseSearchEntities("<html>challenge</html>").recognized());
        assertFalse(Liepin.parseSearchEntities("{broken").recognized());
    }
}
