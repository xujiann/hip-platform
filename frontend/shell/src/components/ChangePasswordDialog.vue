<template>
  <el-dialog
    :model-value="modelValue"
    :title="forced ? '首次登录须修改初始密码' : '修改密码'"
    width="420px"
    :close-on-click-modal="!forced"
    :close-on-press-escape="!forced"
    :show-close="!forced"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
    @closed="reset"
  >
    <el-alert
      v-if="forced"
      type="warning"
      :closable="false"
      show-icon
      title="初始密码由管理员设置，须改为只有本人知道的密码后才能使用系统"
      style="margin-bottom: 16px"
    />
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <el-form-item label="原密码" prop="oldPassword">
        <el-input v-model="form.oldPassword" type="password" show-password autocomplete="off" />
      </el-form-item>
      <el-form-item label="新密码" prop="newPassword">
        <el-input v-model="form.newPassword" type="password" show-password autocomplete="off"
                  placeholder="至少 8 位，须含字母和数字" />
      </el-form-item>
      <el-form-item label="确认新密码" prop="confirmPassword">
        <el-input v-model="form.confirmPassword" type="password" show-password autocomplete="off" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button v-if="!forced" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">确认修改</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import client from '../api/client'

defineProps<{
  modelValue: boolean
  /** 强制改密模式：不可关闭，改完由父组件决定去向 */
  forced?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  /** 改密成功；带出新密码供强制改密流程自动重新登录 */
  (e: 'success', newPassword: string): void
}>()

const formRef = ref()
const saving = ref(false)
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

// 与后端 PasswordPolicy 同一口径：就地提示，不等 1007 整表打回
const rules = {
  oldPassword: [{ required: true, message: '请填写原密码', trigger: 'blur' }],
  newPassword: [{
    validator: (_r: unknown, v: string, cb: (e?: Error) => void) => {
      if (!v) return cb(new Error('请填写新密码'))
      if (v.length < 8) return cb(new Error('密码不能少于 8 位'))
      if (!/[A-Za-z]/.test(v) || !/\d/.test(v)) return cb(new Error('密码须同时包含字母和数字'))
      if (v === form.oldPassword) return cb(new Error('新密码不能与原密码相同'))
      cb()
    }, trigger: 'blur',
  }],
  confirmPassword: [{
    validator: (_r: unknown, v: string, cb: (e?: Error) => void) => {
      if (!v) return cb(new Error('请再次填写新密码'))
      if (v !== form.newPassword) return cb(new Error('两次输入的新密码不一致'))
      cb()
    }, trigger: 'blur',
  }],
}

function reset() {
  form.oldPassword = ''
  form.newPassword = ''
  form.confirmPassword = ''
  formRef.value?.clearValidate()
}

async function submit() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  saving.value = true
  try {
    // 非 0 码（1006/1007/1008）由 client 拦截器统一弹错并 reject，这里只处理成功分支
    await client.post('/auth/change-password', {
      oldPassword: form.oldPassword,
      newPassword: form.newPassword,
    })
    const newPwd = form.newPassword
    emit('update:modelValue', false)
    emit('success', newPwd)
  } catch {
    /* 已由拦截器提示 */
  } finally {
    saving.value = false
  }
}
</script>
