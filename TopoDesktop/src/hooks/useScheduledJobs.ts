// Copyright 2025 OPPO

// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at

//     http://www.apache.org/licenses/LICENSE-2.0

// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import type { CreateScheduledJobPayload, ScheduledJob } from '../types/scheduledJob'
import {
  refreshJobs as fetchJobsFromServer,
  createJob,
  deleteJob,
  replaceJob,
  partitionByStatus,
  tickDueJobVerification,
  getInitialJobsFromCache,
} from '../services/scheduledJobService'

export type ScheduledTab = 'scheduled' | 'running' | 'completed'

export function useScheduledJobs() {
  const [jobs, setJobs] = useState<ScheduledJob[]>(() => getInitialJobsFromCache().jobs)
  const [fetchedAt, setFetchedAt] = useState(() => getInitialJobsFromCache().fetchedAt)
  const [now, setNow] = useState(() => Date.now())
  const [activeTab, setActiveTab] = useState<ScheduledTab>('scheduled')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const jobsRef = useRef(jobs)
  const fetchedAtRef = useRef(fetchedAt)
  jobsRef.current = jobs
  fetchedAtRef.current = fetchedAt

  const refresh = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const list = await fetchJobsFromServer()
      setJobs(list)
      setFetchedAt(Date.now())
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh])

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(t)
  }, [])

  useEffect(() => {
    const t = setInterval(async () => {
      const fresh = await tickDueJobVerification(jobsRef.current, fetchedAtRef.current, Date.now())
      if (fresh) {
        setJobs(fresh)
        setFetchedAt(Date.now())
      }
    }, 5000)
    return () => clearInterval(t)
  }, [])

  useEffect(() => {
    const t = setInterval(() => {
      refresh()
    }, 60_000)
    return () => clearInterval(t)
  }, [refresh])

  const partitioned = useMemo(() => partitionByStatus(jobs), [jobs])

  const create = useCallback(async (payload: CreateScheduledJobPayload) => {
    const { job, list } = await createJob(payload)
    setJobs(list)
    setFetchedAt(Date.now())
    return job
  }, [])

  const replace = useCallback(async (oldId: string, payload: CreateScheduledJobPayload) => {
    const { job, list } = await replaceJob(oldId, payload)
    setJobs(list)
    setFetchedAt(Date.now())
    return job
  }, [])

  const remove = useCallback(async (id: string) => {
    const list = await deleteJob(id)
    setJobs(list)
    setFetchedAt(Date.now())
  }, [])

  return {
    jobs,
    fetchedAt,
    now,
    activeTab,
    setActiveTab,
    loading,
    error,
    refresh,
    partitioned,
    create,
    replace,
    remove,
  }
}
