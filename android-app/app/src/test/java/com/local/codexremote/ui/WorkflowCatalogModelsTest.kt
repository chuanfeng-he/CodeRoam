package com.local.codexremote.ui

import com.local.codexremote.data.CodexJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowCatalogModelsTest {
    @Test
    fun buildsWorkflowCatalogParamsFromCurrentWorkspace() {
        val skills = buildSkillsListParams(cwd = "/home/user/project", forceReload = true)
        val plugins = buildPluginListParams(cwd = "/home/user/project")
        val apps = buildAppsListParams(threadId = "thread_1", cursor = "next", forceRefetch = true)
        val mcp = buildMcpServerStatusListParams(cursor = "mcp_next")
        val hooks = buildHooksListParams(cwd = "/home/user/project")

        assertEquals("/home/user/project", skills["cwds"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("true", skills["forceReload"]!!.jsonPrimitive.content)
        assertEquals("/home/user/project", plugins["cwds"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("thread_1", apps["threadId"]!!.jsonPrimitive.content)
        assertEquals("next", apps["cursor"]!!.jsonPrimitive.content)
        assertEquals("true", apps["forceRefetch"]!!.jsonPrimitive.content)
        assertEquals("mcp_next", mcp["cursor"]!!.jsonPrimitive.content)
        assertEquals("full", mcp["detail"]!!.jsonPrimitive.content)
        assertEquals("/home/user/project", hooks["cwds"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun omitsOptionalWorkflowCatalogParamsWhenContextIsMissing() {
        val skills = buildSkillsListParams(cwd = null, forceReload = false)
        val plugins = buildPluginListParams(cwd = "")
        val apps = buildAppsListParams(threadId = null, cursor = null, forceRefetch = false)
        val hooks = buildHooksListParams(cwd = null)

        assertNull(skills["cwds"])
        assertNull(skills["forceReload"])
        assertNull(plugins["cwds"])
        assertNull(apps["threadId"])
        assertNull(apps["cursor"])
        assertNull(apps["forceRefetch"])
        assertNull(hooks["cwds"])
    }

    @Test
    fun parsesSkillsCatalogByWorkspace() {
        val result = CodexJson.parseToJsonElement(
            """
            {
              "data": [
                {
                  "cwd": "/home/user/project",
                  "skills": [
                    {
                      "name": "webapp-testing",
                      "description": "Test local web apps",
                      "shortDescription": "Browser tests",
                      "interface": {
                        "displayName": "Web App Testing",
                        "shortDescription": "Verify UI",
                        "defaultPrompt": "Run a smoke test"
                      },
                      "path": "/home/user/.codex/skills/webapp-testing/SKILL.md",
                      "scope": "user",
                      "enabled": true
                    }
                  ],
                  "errors": [{"message": "bad skill"}]
                }
              ]
            }
            """.trimIndent()
        ).jsonObject

        val catalog = parseSkillsCatalog(result)

        assertEquals(1, catalog.groups.size)
        assertEquals("/home/user/project", catalog.groups.single().cwd)
        assertEquals("bad skill", catalog.groups.single().errors.single())
        val skill = catalog.groups.single().skills.single()
        assertEquals("webapp-testing", skill.name)
        assertEquals("Web App Testing", skill.displayName)
        assertEquals("Verify UI", skill.description)
        assertEquals("Run a smoke test", skill.defaultPrompt)
        assertTrue(skill.enabled)
    }

    @Test
    fun parsesPluginCatalogByMarketplace() {
        val result = CodexJson.parseToJsonElement(
            """
            {
              "marketplaces": [
                {
                  "name": "local",
                  "path": "/home/user/.codex/plugins/marketplace.json",
                  "plugins": [
                    {
                      "id": "github",
                      "name": "github",
                      "installed": true,
                      "enabled": false,
                      "availability": "available",
                      "source": "local",
                      "keywords": ["issues"],
                      "interface": {
                        "displayName": "GitHub",
                        "shortDescription": "Work with issues",
                        "capabilities": ["issues", "pull requests"],
                        "defaultPrompt": ["List my issues"],
                        "brandColor": "#111111"
                      }
                    }
                  ]
                }
              ],
              "marketplaceLoadErrors": [{"message": "remote unavailable"}],
              "featuredPluginIds": ["github"]
            }
            """.trimIndent()
        ).jsonObject

        val catalog = parsePluginCatalog(result)

        assertEquals(listOf("github"), catalog.featuredPluginIds)
        assertEquals("remote unavailable", catalog.errors.single())
        val plugin = catalog.marketplaces.single().plugins.single()
        assertEquals("github", plugin.id)
        assertEquals("GitHub", plugin.displayName)
        assertEquals("Work with issues", plugin.description)
        assertEquals(listOf("issues", "pull requests"), plugin.capabilities)
        assertEquals(listOf("List my issues"), plugin.defaultPrompts)
        assertTrue(plugin.installed)
        assertFalse(plugin.enabled)
    }

    @Test
    fun parsesAutomationCatalogPages() {
        val appsResult = CodexJson.parseToJsonElement(
            """
            {
              "data": [
                {
                  "id": "slack",
                  "name": "Slack",
                  "description": "Send messages",
                  "isAccessible": true,
                  "isEnabled": false,
                  "pluginDisplayNames": ["Slack Plugin"],
                  "installUrl": "https://example.com/slack"
                }
              ],
              "nextCursor": "apps_next"
            }
            """.trimIndent()
        ).jsonObject
        val mcpResult = CodexJson.parseToJsonElement(
            """
            {
              "data": [
                {
                  "name": "github",
                  "tools": {"create_issue": {"name": "create_issue", "description": "Create issue"}},
                  "resources": [{"uri": "repo://one", "name": "Repo"}],
                  "resourceTemplates": [{"uriTemplate": "repo://{owner}", "name": "Repo template"}],
                  "authStatus": "oAuth"
                }
              ],
              "nextCursor": null
            }
            """.trimIndent()
        ).jsonObject
        val hooksResult = CodexJson.parseToJsonElement(
            """
            {
              "data": [
                {
                  "cwd": "/home/user/project",
                  "hooks": [
                    {
                      "key": "fmt",
                      "eventName": "preToolUse",
                      "handlerType": "command",
                      "matcher": "apply_patch",
                      "command": "npm test",
                      "timeoutSec": 30,
                      "sourcePath": "/home/user/.codex/hooks.toml",
                      "enabled": true,
                      "isManaged": false,
                      "trustStatus": "trusted"
                    }
                  ],
                  "warnings": ["slow"],
                  "errors": [{"message": "bad hook"}]
                }
              ]
            }
            """.trimIndent()
        ).jsonObject

        val apps = parseAppsCatalog(appsResult)
        val mcp = parseMcpCatalog(mcpResult)
        val hooks = parseHooksCatalog(hooksResult)

        assertEquals("apps_next", apps.nextCursor)
        assertEquals("Slack", apps.apps.single().name)
        assertEquals(listOf("Slack Plugin"), apps.apps.single().pluginDisplayNames)
        assertFalse(apps.apps.single().isEnabled)
        assertEquals("github", mcp.servers.single().name)
        assertEquals(1, mcp.servers.single().toolCount)
        assertEquals(1, mcp.servers.single().resourceCount)
        assertEquals("oAuth", mcp.servers.single().authStatus)
        assertEquals("fmt", hooks.groups.single().hooks.single().key)
        assertEquals("slow", hooks.groups.single().warnings.single())
        assertEquals("bad hook", hooks.groups.single().errors.single())
    }

    @Test
    fun workflowStateUpdatesPreserveExistingCatalogOnFailure() {
        val previous = SkillsCatalogState(
            groups = listOf(SkillGroup(cwd = "/home/user", skills = listOf(SkillItem(name = "existing")))),
            isLoading = true
        )

        val failed = previous.withWorkflowLoadFailure("skills/list failed")

        assertEquals("existing", failed.groups.single().skills.single().name)
        assertFalse(failed.isLoading)
        assertEquals("skills/list failed", failed.error)
    }
}
