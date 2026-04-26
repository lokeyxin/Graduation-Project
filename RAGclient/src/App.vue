<script setup>
import { computed, onBeforeUnmount, onMounted, ref, nextTick } from 'vue'
import { createSession, deleteDocument, deleteSession, listDocuments, listMessages, listSessions, login, register, streamChat, uploadDocument } from './services/api'

const username = ref('demo01')
const password = ref('123456')
const confirmPassword = ref('')
const registerDisplayName = ref('')
const errorMessage = ref('')
const registerMessage = ref('')
const loginLoading = ref(false)
const registerLoading = ref(false)
const isRegisterMode = ref(false)
const token = ref('')
const displayName = ref('')

const sessionList = ref([])
const deletingSessionId = ref(null)
const activeSessionId = ref(null)
const messageList = ref([])
const inputMessage = ref('')
const isGenerating = ref(false)
const messageViewport = ref(null)
const currentView = ref('chat')

const documentList = ref([])
const uploadFile = ref(null)
const uploadFileInput = ref(null)
const uploadLoading = ref(false)
const deletingDocumentId = ref(null)
const knowledgeMessage = ref('')
const knowledgeError = ref('')
const documentPollingTimer = ref(null)
const documentPollingErrorCount = ref(0)

const DOCUMENT_PROCESSING_STATUS = 2
const DOCUMENT_POLLING_INTERVAL_MS = 3000
const DOCUMENT_POLLING_MAX_ERRORS = 3

const isLoggedIn = computed(() => Boolean(token.value))

function autoScrollToBottom() {
  nextTick(() => {
    if (messageViewport.value) {
      messageViewport.value.scrollTop = messageViewport.value.scrollHeight
    }
  })
}

async function handleLogin() {
  if (loginLoading.value) return
  errorMessage.value = ''
  registerMessage.value = ''
  loginLoading.value = true
  try {
    const response = await login({
      username: username.value,
      password: password.value,
    })

    token.value = response.token
    displayName.value = response.displayName
    localStorage.setItem('ragToken', response.token)
    localStorage.setItem('ragDisplayName', response.displayName)

    await loadSessionList()
    await loadDocumentListSilently()
  } catch (error) {
    token.value = ''
    displayName.value = ''
    sessionList.value = []
    messageList.value = []
    activeSessionId.value = null
    localStorage.removeItem('ragToken')
    localStorage.removeItem('ragDisplayName')
    errorMessage.value = error?.message || '登录失败，请重试。'
  } finally {
    loginLoading.value = false
  }
}

function switchAuthMode(registerMode) {
  isRegisterMode.value = registerMode
  errorMessage.value = ''
  registerMessage.value = ''
  password.value = ''
  confirmPassword.value = ''
  registerDisplayName.value = ''
  if (!registerMode) {
    username.value = username.value.trim()
  }
}

async function handleRegister() {
  if (registerLoading.value) return
  errorMessage.value = ''
  registerMessage.value = ''

  const normalizedUsername = username.value.trim()
  const normalizedDisplayName = registerDisplayName.value.trim()

  if (!normalizedUsername) {
    errorMessage.value = '用户名不能为空。'
    return
  }
  if (!password.value) {
    errorMessage.value = '密码不能为空。'
    return
  }
  if (password.value.length < 6) {
    errorMessage.value = '密码至少需要6位。'
    return
  }
  if (password.value !== confirmPassword.value) {
    errorMessage.value = '两次输入的密码不一致。'
    return
  }

  registerLoading.value = true
  try {
    await register({
      username: normalizedUsername,
      password: password.value,
      displayName: normalizedDisplayName || normalizedUsername,
    })

    registerMessage.value = '注册成功，请使用新账号登录。'
    isRegisterMode.value = false
    username.value = normalizedUsername
    password.value = ''
    confirmPassword.value = ''
    registerDisplayName.value = ''
  } catch (error) {
    errorMessage.value = error?.message || '注册失败，请重试。'
  } finally {
    registerLoading.value = false
  }
}

function logout() {
  stopDocumentPolling()
  token.value = ''
  displayName.value = ''
  sessionList.value = []
  messageList.value = []
  activeSessionId.value = null
  currentView.value = 'chat'
  documentList.value = []
  uploadFile.value = null
  knowledgeMessage.value = ''
  knowledgeError.value = ''
  localStorage.removeItem('ragToken')
  localStorage.removeItem('ragDisplayName')
}

function hasProcessingDocuments(documents) {
  return Array.isArray(documents) && documents.some((doc) => doc.status === DOCUMENT_PROCESSING_STATUS)
}

function stopDocumentPolling() {
  if (documentPollingTimer.value !== null) {
    clearInterval(documentPollingTimer.value)
    documentPollingTimer.value = null
  }
}

function startDocumentPolling() {
  if (documentPollingTimer.value !== null || currentView.value !== 'knowledge') {
    return
  }

  documentPollingErrorCount.value = 0
  documentPollingTimer.value = setInterval(async () => {
    try {
      const documents = await listDocuments()
      documentList.value = documents
      documentPollingErrorCount.value = 0

      if (!hasProcessingDocuments(documents)) {
        stopDocumentPolling()
      }
    } catch (_) {
      documentPollingErrorCount.value += 1
      if (documentPollingErrorCount.value >= DOCUMENT_POLLING_MAX_ERRORS) {
        stopDocumentPolling()
        knowledgeError.value = '文档状态自动刷新失败，请稍后手动重试。'
      }
    }
  }, DOCUMENT_POLLING_INTERVAL_MS)
}

async function loadDocumentListSilently() {
  try {
    const documents = await listDocuments()
    documentList.value = documents

    if (currentView.value === 'knowledge' && hasProcessingDocuments(documents)) {
      startDocumentPolling()
    } else {
      stopDocumentPolling()
    }
  } catch (_) {
    documentList.value = []
    stopDocumentPolling()
  }
}

async function loadSessionList() {
  sessionList.value = await listSessions()
  if (!sessionList.value.length) {
    await createNewSession()
    return
  }

  activeSessionId.value = sessionList.value[0].sessionId
  await loadMessages(activeSessionId.value)
}

async function createNewSession() {
  const newSession = await createSession({ title: '新对话' })
  sessionList.value = [newSession, ...sessionList.value]
  activeSessionId.value = newSession.sessionId
  messageList.value = []
  return newSession
}

async function handleDeleteSession(event, sessionId) {
  event.stopPropagation()
  if (deletingSessionId.value !== null) return

  const confirmed = window.confirm('确认删除该对话吗？删除后不可恢复。')
  if (!confirmed) return

  deletingSessionId.value = sessionId
  errorMessage.value = ''

  try {
    await deleteSession(sessionId)
    await loadSessionList()
  } catch (error) {
    errorMessage.value = error?.message || '删除会话失败，请重试。'
  } finally {
    deletingSessionId.value = null
  }
}

async function loadMessages(sessionId) {
  activeSessionId.value = sessionId
  messageList.value = await listMessages(sessionId)
  autoScrollToBottom()
}

async function sendMessage() {
  if (isGenerating.value) return

  const userContent = inputMessage.value.trim()
  if (!userContent) return

  inputMessage.value = ''
  isGenerating.value = true

  let sessionId = activeSessionId.value
  let assistantMessage = null

  try {
    if (!sessionId) {
      const newSession = await createNewSession()
      sessionId = newSession.sessionId
    }

    messageList.value.push({
      messageId: Date.now(),
      sessionId,
      role: 'user',
      content: userContent,
      createdAt: new Date().toISOString(),
    })

    assistantMessage = {
      messageId: Date.now() + 1,
      sessionId,
      role: 'assistant',
      content: '',
      createdAt: new Date().toISOString(),
    }
    messageList.value.push(assistantMessage)
    autoScrollToBottom()

    await streamChat(
      { sessionId, message: userContent },
      {
        onMessage(chunk) {
          assistantMessage.content += chunk
          autoScrollToBottom()
        },
        onDone() {},
        onError(error) {
          assistantMessage.content = error || '生成失败，请重试。'
        },
      },
    )

    await loadMessages(sessionId)
  } catch (error) {
    if (assistantMessage) {
      assistantMessage.content = error?.message || '生成失败，请重试。'
    } else {
      errorMessage.value = error?.message || '发送失败，请重试。'
    }
  } finally {
    isGenerating.value = false
  }
}

function handleKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    sendMessage()
  }
}

function switchView(view) {
  if (view !== 'knowledge') {
    stopDocumentPolling()
  }

  currentView.value = view
  if (view === 'knowledge') {
    loadDocumentListSilently()
  }
}

function handleUploadFileChange(event) {
  const file = event.target.files?.[0]
  uploadFile.value = file || null
  knowledgeMessage.value = ''
  knowledgeError.value = ''
}

function statusText(status) {
  if (status === 1) return '已入库'
  if (status === 2) return '处理中'
  if (status === 0) return '失败'
  return '未知'
}

async function submitDocumentUpload() {
  if (uploadLoading.value) return
  if (!uploadFile.value) {
    knowledgeError.value = '请先选择支持格式文件（.docx/.pdf/.md/.json/.txt/.jsonl）。'
    return
  }

  knowledgeMessage.value = ''
  knowledgeError.value = ''
  uploadLoading.value = true

  try {
    let result
    try {
      result = await uploadDocument(uploadFile.value, false)
      knowledgeMessage.value = `上传成功：${result.documentName}（后台正在解析并入库，状态将自动刷新）`
    } catch (error) {
      if (error?.code === 'D409') {
        const shouldOverwrite = window.confirm('已存在同名文件，是否替换原文件并重新入库？')
        if (!shouldOverwrite) {
          knowledgeMessage.value = '已取消替换，原文件保持不变。'
          return
        }
        result = await uploadDocument(uploadFile.value, true)
        knowledgeMessage.value = `已替换上传：${result.documentName}（后台正在重新解析并入库）`
      } else {
        throw error
      }
    }

    uploadFile.value = null
    if (uploadFileInput.value) {
      uploadFileInput.value.value = ''
    }
    await loadDocumentListSilently()
  } catch (error) {
    knowledgeError.value = error?.message || '上传失败，请重试。'
  } finally {
    uploadLoading.value = false
  }
}

async function handleDeleteDocument(doc) {
  if (!doc?.documentId || deletingDocumentId.value !== null) return
  if (doc.status === DOCUMENT_PROCESSING_STATUS) {
    knowledgeError.value = '文档处理中，暂不支持删除。'
    return
  }

  const confirmed = window.confirm(`确认删除文档“${doc.documentName}”吗？删除后无法恢复。`)
  if (!confirmed) return

  knowledgeMessage.value = ''
  knowledgeError.value = ''
  deletingDocumentId.value = doc.documentId

  try {
    await deleteDocument(doc.documentId)
    knowledgeMessage.value = `删除成功：${doc.documentName}`
    await loadDocumentListSilently()
  } catch (error) {
    knowledgeError.value = error?.message || '删除失败，请重试。'
  } finally {
    deletingDocumentId.value = null
  }
}

onMounted(async () => {
  // 按需求强制每次进入都显示登录页。
  localStorage.removeItem('ragToken')
  localStorage.removeItem('ragDisplayName')
  token.value = ''
  displayName.value = ''
})

onBeforeUnmount(() => {
  stopDocumentPolling()
})
</script>

<template>
  <div class="appShell">
    <div v-if="!isLoggedIn" class="loginPanel">
      <h1>AI 智能客服系统</h1>
      <p>{{ isRegisterMode ? '创建新账号后，返回登录页继续使用。' : '使用账号登录后开始对话。' }}</p>
      <label>
        用户名
        <input v-model="username" type="text" placeholder="请输入用户名" />
      </label>

      <template v-if="isRegisterMode">
        <label>
          显示名（可选）
          <input v-model="registerDisplayName" type="text" placeholder="默认为用户名" />
        </label>
        <label>
          密码
          <input v-model="password" type="password" placeholder="请输入密码（至少6位）" />
        </label>
        <label>
          确认密码
          <input v-model="confirmPassword" type="password" placeholder="请再次输入密码" @keydown.enter="handleRegister" />
        </label>
        <button class="loginActionButton" :disabled="registerLoading" @click="handleRegister">
          {{ registerLoading ? '注册中...' : '确定注册' }}
        </button>
      </template>

      <template v-else>
        <label>
          密码
          <input v-model="password" type="password" placeholder="请输入密码" @keydown.enter="handleLogin" />
        </label>
        <button class="loginActionButton" :disabled="loginLoading" @click="handleLogin">
          {{ loginLoading ? '登录中...' : '确定登录' }}
        </button>
      </template>

      <button
        class="authSwitchButton"
        :disabled="loginLoading || registerLoading"
        @click="switchAuthMode(!isRegisterMode)"
      >
        {{ isRegisterMode ? '已有账号？返回登录' : '没有账号？去注册' }}
      </button>
      <span v-if="registerMessage" class="successText">{{ registerMessage }}</span>
      <span v-if="errorMessage" class="errorText">{{ errorMessage }}</span>
    </div>

    <div v-else class="chatLayout">
      <aside class="leftSidebar">
        <div class="sidebarHeader">
          <button class="primaryButton" @click="createNewSession">+ 新对话</button>
        </div>
        <div class="sidebarNav">
          <button class="sessionItem" :class="{ active: currentView === 'chat' }" @click="switchView('chat')">对话区</button>
          <button class="sessionItem" :class="{ active: currentView === 'knowledge' }" @click="switchView('knowledge')">知识库</button>
        </div>
        <div class="sessionList">
          <div
            v-for="session in sessionList"
            :key="session.sessionId"
            class="sessionRow"
          >
            <button
              class="sessionItem"
              :class="{ active: session.sessionId === activeSessionId }"
              :disabled="currentView !== 'chat'"
              @click="loadMessages(session.sessionId)"
            >
              {{ session.title }}
            </button>
            <button
              class="sessionDeleteButton"
              :disabled="deletingSessionId === session.sessionId"
              @click="handleDeleteSession($event, session.sessionId)"
            >
              {{ deletingSessionId === session.sessionId ? '...' : '删' }}
            </button>
          </div>
        </div>
        <div class="sidebarFooter">
          <span>{{ displayName }}</span>
          <button class="ghostButton" @click="logout">退出</button>
        </div>
      </aside>

      <main v-if="currentView === 'chat'" class="chatMain">
        <section ref="messageViewport" class="messageViewport">
          <div
            v-for="message in messageList"
            :key="message.messageId"
            class="messageRow"
            :class="message.role"
          >
            <div class="bubble">{{ message.content }}</div>
          </div>
        </section>

        <footer class="inputArea">
          <textarea
            v-model="inputMessage"
            placeholder="请输入问题，点击“开始对话”发送（Shift + Enter 换行）"
            @keydown="handleKeydown"
          />
          <button
            class="primaryButton"
            :disabled="isGenerating"
            @click="sendMessage"
          >
            {{ isGenerating ? '生成中...' : '开始对话' }}
          </button>
        </footer>
      </main>

      <main v-else class="knowledgeMain">
        <section class="knowledgePanel">
          <h2>知识库文件上传</h2>
          <p>支持 Word(.docx)、PDF(.pdf)、Markdown(.md)、JSON(.json)、TXT(.txt)、JSONL(.jsonl)，上传后会自动解析并入库。</p>

          <div class="uploadRow">
            <input
              ref="uploadFileInput"
              type="file"
              accept=".docx,.pdf,.md,.json,.txt,.jsonl"
              @change="handleUploadFileChange"
            />
            <button class="primaryButton" :disabled="uploadLoading" @click="submitDocumentUpload">
              {{ uploadLoading ? '上传处理中...' : '上传并入库' }}
            </button>
          </div>

          <div v-if="uploadFile" class="uploadHint">已选择：{{ uploadFile.name }}</div>
          <div v-if="knowledgeMessage" class="successText">{{ knowledgeMessage }}</div>
          <div v-if="knowledgeError" class="errorText">{{ knowledgeError }}</div>
        </section>

        <section class="knowledgePanel">
          <h3>我的文档</h3>
          <div v-if="!documentList.length" class="emptyText">暂无文档，请先上传。</div>
          <div v-else class="docList">
            <div v-for="doc in documentList" :key="doc.documentId" class="docRow">
              <div class="docName">{{ doc.documentName }}</div>
              <div class="docMeta">
                <span class="docStatus">{{ statusText(doc.status) }}</span>
                <span>{{ new Date(doc.createdAt).toLocaleString() }}</span>
                <button
                  class="dangerButton"
                  :disabled="doc.status === DOCUMENT_PROCESSING_STATUS || deletingDocumentId === doc.documentId"
                  @click="handleDeleteDocument(doc)"
                >
                  {{ deletingDocumentId === doc.documentId ? '删除中...' : '删除' }}
                </button>
              </div>
            </div>
          </div>
        </section>
      </main>
    </div>
  </div>
</template>
