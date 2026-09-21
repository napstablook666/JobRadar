package com.jobradar.application.service;

import com.jobradar.application.entity.Job51Entity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class Job51SearchJsonTest {

    @Test
    void parsesNestedJobSnapshotIncludingDescriptionAndLink() {
        List<Job51Entity> result = Job51Service.parseSearchEntities("""
                {"data":{"items":[
                  {"job":{"jobId":"1001","jobName":"应用工程师","jobDescription":"负责设备现场支持与客户培训","jobHref":"//example.test/job/1001"},
                   "company":{"companyId":"88","companyName":"示例公司"},"hrName":"招聘专员"},
                  {"jobId":1002,"title":"测试工程师","description":"负责测试方案与问题跟踪"}
                ]}}
                """);

        assertEquals(2, result.size());
        assertEquals(1001L, result.get(0).getJobId());
        assertEquals("应用工程师", result.get(0).getJobTitle());
        assertEquals("负责设备现场支持与客户培训", result.get(0).getJobDescription());
        assertEquals("//example.test/job/1001", result.get(0).getJobLink());
        assertEquals(88L, result.get(0).getCompId());
        assertEquals("示例公司", result.get(0).getCompName());
        assertEquals("招聘专员", result.get(0).getHrName());
        assertEquals(1002L, result.get(1).getJobId());
        assertNotNull(result.get(1).getJobLink());
    }

    @Test
    void acceptsTopLevelListAndFallbackGeneratedLink() {
        List<Job51Entity> result = Job51Service.parseSearchEntities(
                "{\"list\":[{\"job_id\":2001,\"jobTitle\":\"设备支持\",\"jobDesc\":\"现场支持与维护\"}]}"
        );

        assertEquals(1, result.size());
        assertEquals(2001L, result.get(0).getJobId());
        assertEquals("https://jobs.51job.com/all/2001.html", result.get(0).getJobLink());
    }

    @Test
    void parsesLiveSearchDescriptionFieldBeforeLegacyAliases() {
        List<Job51Entity> result = Job51Service.parseSearchEntities("""
                {"resultbody":{"job":{"items":[
                  {"jobId":"3001","jobName":"现场应用工程师",
                   "jobDescribe":"负责设备安装、现场培训与客户技术支持",
                   "description":"旧详情摘要","jobHref":"https://jobs.example.test/city/3001.html"}
                ]}}}
                """);

        assertEquals(1, result.size());
        assertEquals("负责设备安装、现场培训与客户技术支持", result.get(0).getJobDescription());
    }
}
