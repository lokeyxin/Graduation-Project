const API_PROTOCOL = import.meta.env.VITE_API_PROTOCOL || window.location.protocol.replace(':', '') || 'http'
const API_HOST = import.meta.env.VITE_API_HOST || window.location.hostname || 'localhost'
const API_PORT = import.meta.env.VITE_API_PORT || '8080'
const API_BASE_URL = `${API_PROTOCOL}://${API_HOST}:${API_PORT}/api/v1`

function buildNetworkErrorMessage(error) {
  const raw = String(error?.message || '')
  if (raw.includes('Failed to fetch') || raw.includes('NetworkError') || raw.includes('Load failed')) {
    return `网络请求失败：无法连接后端服务（${API_BASE_URL}）。请确认 RAGserver 已启动且端口可访问。`
  }
  return raw || '网络请求失败'
}

async function request(path, options = {}) {
  const token = localStorage.getItem('ragToken')
  const isFormData = options.body instanceof FormData
  const headers = {
    ...(isFormData ? {} : { 'Content-Type': 'application/json' }),
    ...(options.headers || {}),
  }

  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  let response
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      ...options,
      headers,
    })
  } catch (error) {
    throw new Error(buildNetworkErrorMessage(error))
  }

  let data
  try {
    data = await response.json()
  } catch (error) {
    throw new Error('服务响应格式异常，请检查后端接口返回。')
  }

  if (!response.ok || !data.success) {
    const error = new Error(data.message || '请求失败')
    error.code = data.code
    throw error
  }

  return data.data
}

export function login(payload) {
  return request('/auth/login', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function register(payload) {
  return request('/auth/register', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function createSession(payload = { title: '新对话' }) {
  return request('/sessions', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function listSessions() {
  return request('/sessions')
}

export function listMessages(sessionId) {
  return request(`/sessions/${sessionId}/messages`)
}

export function deleteSession(sessionId) {
  return request(`/sessions/${sessionId}`, {
    method: 'DELETE',
  })
}

export function listDocuments() {
  return request('/documents')
}

export function uploadDocument(file, overwrite = false) {
  const formData = new FormData()
  formData.append('file', file)
  return request(`/documents/upload?overwrite=${overwrite}`, {
    method: 'POST',
    body: formData,
  })
}

export function deleteDocument(documentId) {
  return request(`/documents/${documentId}`, {
    method: 'DELETE',
  })
}

export async function streamChat(payload, handlers) {
  const token = localStorage.getItem('ragToken')
  let response
  try {
    response = await fetch(`${API_BASE_URL}/chat/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify(payload),
    })
  } catch (error) {
    throw new Error(buildNetworkErrorMessage(error))
  }

  if (!response.ok || !response.body) {
    throw new Error('流式连接失败')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let doneEventReceived = false

  function splitEventBlocks(rawBuffer) {
    const blocks = rawBuffer.split(/\r?\n\r?\n/)
    return {
      blocks: blocks.slice(0, -1),
      remainder: blocks[blocks.length - 1] || '',
    }
  }

  function parseEventBlock(eventBlock) {
    const lines = eventBlock.split(/\r?\n/)
    let eventName = 'message'
    let eventData = ''

    lines.forEach((line) => {
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
      }
      if (line.startsWith('data:')) {
        const segment = line.slice(5)
        eventData += segment.startsWith(' ') ? segment.slice(1) : segment
      }
    })

    return { eventName, eventData }
  }

  function dispatchEvent(eventName, eventData) {
    if (eventName === 'message' && handlers?.onMessage) {
      handlers.onMessage(eventData)
    } else if (eventName === 'done' && handlers?.onDone) {
      doneEventReceived = true
      handlers.onDone()
    } else if (eventName === 'error' && handlers?.onError) {
      handlers.onError(eventData)
    }
  }

  while (true) {
    const { value, done } = await reader.read()
    if (done) {
      const tail = buffer.trim()
      if (tail) {
        const { eventName, eventData } = parseEventBlock(tail)
        dispatchEvent(eventName, eventData)
      }
      if (!doneEventReceived && handlers?.onDone) {
        handlers.onDone()
      }
      break
    }

    buffer += decoder.decode(value, { stream: true })
    const { blocks, remainder } = splitEventBlocks(buffer)
    buffer = remainder

    blocks.forEach((eventBlock) => {
      const { eventName, eventData } = parseEventBlock(eventBlock)
      dispatchEvent(eventName, eventData)
    })
  }
}
