package com.poolguard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:poolguard-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class ApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Test void loginSettingsPolicyAndLogout() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/api/accounts")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"wrong\"}")).andExpect(status().isUnauthorized());
        var response=mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"pool-admin\"}")).andExpect(status().isOk()).andReturn();
        String auth="Bearer "+mapper.readTree(response.getResponse().getContentAsString()).path("token").asText();
        mvc.perform(get("/api/accounts").header("Authorization",auth)).andExpect(status().isOk());
        mvc.perform(get("/api/accounts").param("project","codex-proxy-rs").header("Authorization",auth))
            .andExpect(status().isOk());
        mvc.perform(get("/api/accounts").param("search","no-such-account").header("Authorization",auth))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(patch("/api/settings").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON)
            .content("{\"scheduleValue\":2,\"scheduleUnit\":\"days\",\"restorePasses\":3}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.scheduleValue").value(2));
        mvc.perform(get("/api/settings").header("Authorization",auth)).andExpect(jsonPath("$.scheduleUnit").value("days"));
        mvc.perform(patch("/api/settings").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON)
            .content("{\"scheduleValue\":0,\"scheduleUnit\":\"days\",\"restorePasses\":3}"))
            .andExpect(status().isBadRequest());
        var created=mvc.perform(post("/api/check-policies").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"custom question\",\"answer\":\"custom answer\",\"model\":\"reasoning\"}"))
            .andExpect(status().isOk()).andReturn();
        String id=mapper.readTree(created.getResponse().getContentAsString()).path("id").asText();
        org.assertj.core.api.Assertions.assertThat(mapper.readTree(created.getResponse().getContentAsString()).path("matchMode").asText()).isEqualTo("FUZZY");
        mvc.perform(put("/api/check-policies/"+id).header("Authorization",auth).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"new question\",\"answer\":\"new answer\",\"model\":\"reasoning\",\"matchMode\":\"EXACT\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.matchMode").value("EXACT"));
        mvc.perform(put("/api/check-policies/"+id).header("Authorization",auth).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"new question\",\"answer\":\"new answer\",\"model\":\"reasoning\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.matchMode").value("EXACT"));
        mvc.perform(post("/api/check-policies").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"bad mode\",\"answer\":\"21\",\"model\":\"test\",\"matchMode\":\"UNKNOWN\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/check-policies/"+id).header("Authorization",auth)).andExpect(status().isOk());
        // 批量保存失败必须整体回滚，不能留下半套问题集。
        var before=mvc.perform(get("/api/check-policies").header("Authorization",auth)).andReturn().getResponse().getContentAsString();
        mvc.perform(put("/api/check-policies").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON)
            .content("[{\"policy\":{\"title\":\"temporary\",\"answer\":\"yes\",\"model\":\"test\"}},{\"policy\":{\"title\":\"invalid\",\"answer\":\"keywords:\",\"model\":\"test\"}}]"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/check-policies").header("Authorization",auth)).andExpect(content().json(before));
        mvc.perform(put("/api/check-policies").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON)
            .content("[{\"policy\":{\"title\":\"\",\"answer\":\"yes\",\"model\":\"test\"}}]"))
            .andExpect(status().isBadRequest());
        String batch="[{\"policy\":{\"title\":\"fuzzy question\",\"answer\":\"21\",\"model\":\"test\"}},{\"policy\":{\"title\":\"exact question\",\"answer\":\"8\",\"model\":\"test\",\"matchMode\":\"EXACT\"}}]";
        mvc.perform(put("/api/check-policies").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON).content(batch))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].matchMode").value("FUZZY")).andExpect(jsonPath("$[1].matchMode").value("EXACT"));
        mvc.perform(get("/api/check-policies").header("Authorization",auth))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].matchMode").value("FUZZY")).andExpect(jsonPath("$[1].matchMode").value("EXACT"));
        mvc.perform(post("/api/auth/logout").header("Authorization",auth)).andExpect(status().isOk());
        mvc.perform(get("/api/settings").header("Authorization",auth)).andExpect(status().isUnauthorized());
    }
}
